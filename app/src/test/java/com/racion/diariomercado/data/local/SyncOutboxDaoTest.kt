package com.racion.diariomercado.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.racion.diariomercado.data.local.dao.SyncOutboxDao
import com.racion.diariomercado.data.local.entity.SyncOutOp
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.domain.model.MealSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The queue's own rules, on an in-memory database.
 *
 * ## What belongs here and what belongs in `DiarySyncManagerTest`
 * Everything about **what is stored**: the three collapse rules, the drain order, the `LIMIT`, the
 * pending count. Nothing about the network. The split is deliberate — this file can assert
 * "the queue holds exactly one row and it is the newest" as a fact about storage, whereas the same
 * assertion made through a transport proves two things at once and pins down neither.
 *
 * ## Why the collapse tests use `assertEquals(1, ...)` before checking contents
 * The row count is the actual contract and the easiest thing to get wrong. A payload assertion on
 * its own would pass for a queue that kept both rows and happened to return the newest one for this
 * `LIMIT` — which is the exact bug that would flood the server with superseded edits.
 *
 * `allowMainThreadQueries()` matches `DiaryDaoTest`: Robolectric runs the body on the main thread and
 * an in-memory database gains nothing from an executor the test would have to pump.
 */
@RunWith(RobolectricTestRunner::class)
class SyncOutboxDaoTest {

    private lateinit var db: RacionDatabase
    private lateinit var outboxDao: SyncOutboxDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        outboxDao = db.syncOutboxDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Coalescing: the primary key IS the rule ---------------------------------------------

    /**
     * UPSERT → UPSERT keeps one row, carrying the newest payload.
     *
     * Sending only the newer payload is correct because the server is keyed on `entryId`: applying
     * the older one first and the newer one second reaches the same state, and applying only the
     * newer one reaches it in a single request. The test asserts both halves — one row, newest
     * values — because "one row with the newest values" is the claim and "one row" alone would let a
     * row holding the *oldest* payload pass.
     */
    @Test
    fun `a second upsert for the same entry replaces it instead of piling up`() = runTest {
        outboxDao.enqueue(row(op = SyncOutOp.UPSERT, enqueuedAt = 100L, kcal = 200))
        outboxDao.enqueue(row(op = SyncOutOp.UPSERT, enqueuedAt = 200L, kcal = 999, servings = 3))

        val pending = outboxDao.oldestPending(10)

        assertEquals("one entry must never occupy two queue slots", 1, pending.size)
        assertEquals(999, pending.single().kcal)
        assertEquals(3, pending.single().servings)
        assertEquals("the newer payload wins", 200L, pending.single().enqueuedAtEpochMillis)
    }

    /**
     * DELETE → DELETE keeps one row.
     *
     * Worth its own test even though it looks like the upsert case: a queue that accumulated deletes
     * would make the remote day aggregate decremented once per local tap, and the symptom would be a
     * user's day total drifting negative a week later with nothing in the logs.
     */
    @Test
    fun `a second delete for the same entry replaces it instead of piling up`() = runTest {
        outboxDao.enqueue(row(op = SyncOutOp.DELETE, enqueuedAt = 100L))
        outboxDao.enqueue(row(op = SyncOutOp.DELETE, enqueuedAt = 200L))

        val pending = outboxDao.oldestPending(10)

        assertEquals(1, pending.size)
        assertEquals(SyncOutOp.DELETE, pending.single().operation)
    }

    /**
     * UPSERT → DELETE collapses to a DELETE. This is the one that is not obvious, and it is the
     * reason the whole collapse rule matters.
     *
     * An upsert that arrived first and a delete that arrived second mean the entry no longer exists.
     * Collapsing to UPSERT instead would resurrect it on the server, and nothing local could ever fix
     * that: a later `deleteEntry` for an already-absent id is a successful no-op that never produces
     * another DELETE row, so the ghost entry on the server would be permanent and invisible.
     */
    @Test
    fun `an upsert followed by a delete collapses to the delete`() = runTest {
        outboxDao.enqueue(row(op = SyncOutOp.UPSERT, enqueuedAt = 100L, kcal = 200))
        outboxDao.enqueue(row(op = SyncOutOp.DELETE, enqueuedAt = 200L, kcal = 200))

        val pending = outboxDao.oldestPending(10)

        assertEquals("add then remove must not leave two queued writes for one entry", 1, pending.size)
        assertEquals(
            "the delete has to win, or the server keeps an entry the user removed",
            SyncOutOp.DELETE,
            pending.single().operation
        )
    }

    /**
     * The `op` column round-trips through its constant name.
     *
     * Small, but it pins the encoding: a switch to `valueOf(op.ordinal)` would keep both tests above
     * green on the current enum while silently depending on declaration order, and reordering the
     * two constants would then start reinterpreting every existing row on disk. The stored value is
     * asserted as text for the same reason the entity's KDoc explains it is stored as text.
     */
    @Test
    fun `the operation is stored as its constant name, not its ordinal`() = runTest {
        outboxDao.enqueue(row(op = SyncOutOp.DELETE, enqueuedAt = 100L))

        assertEquals("DELETE", outboxDao.oldestPending(10).single().op)
        assertEquals(SyncOutOp.DELETE, outboxDao.oldestPending(10).single().operation)
    }

    // -- Ordering and bounding ----------------------------------------------------------------

    /**
     * Oldest first, with `entryId` as the tiebreaker.
     *
     * Two rows enqueued at the same millisecond are ordinary — the confirm screen writes fast — and
     * `System.currentTimeMillis()` returning the same value twice is not even unlikely. Without a
     * deterministic tiebreaker a `LIMIT` query can return a different set on each call, so the
     * assertion is on the exact order, not on the set.
     */
    @Test
    fun `oldestPending is ordered by timestamp then by id`() = runTest {
        outboxDao.enqueue(row("entry-c", enqueuedAt = 300L))
        outboxDao.enqueue(row("entry-a", enqueuedAt = 100L))
        outboxDao.enqueue(row("entry-b", enqueuedAt = 200L))

        assertEquals(
            listOf("entry-a", "entry-b", "entry-c"),
            outboxDao.oldestPending(10).map { it.entryId }
        )
    }

    /**
     * Same timestamp: the id decides, and it decides the same way every call.
     *
     * Deliberately enqueued out of alphabetical order, so an implementation that fell back to
     * insertion order or to SQLite's arbitrary row order would return the wrong sequence.
     */
    @Test
    fun `rows enqueued in the same millisecond are ordered by id`() = runTest {
        outboxDao.enqueue(row("entry-z", enqueuedAt = 500L))
        outboxDao.enqueue(row("entry-m", enqueuedAt = 500L))
        outboxDao.enqueue(row("entry-a", enqueuedAt = 500L))

        assertEquals(
            listOf("entry-a", "entry-m", "entry-z"),
            outboxDao.oldestPending(10).map { it.entryId }
        )
    }

    /**
     * `LIMIT` is honoured, and the rows that come back are the oldest ones.
     *
     * A queue that returned everything would make an unbounded drain look like a bounded one, and on
     * a week-old backlog that is a foreground-length network session on a phone.
     */
    @Test
    fun `oldestPending respects its limit and returns the oldest rows`() = runTest {
        outboxDao.enqueue(row("entry-1", enqueuedAt = 100L))
        outboxDao.enqueue(row("entry-2", enqueuedAt = 200L))
        outboxDao.enqueue(row("entry-3", enqueuedAt = 300L))

        val batch = outboxDao.oldestPending(2)

        assertEquals(2, batch.size)
        assertEquals(listOf("entry-1", "entry-2"), batch.map { it.entryId })
    }

    // -- Removal and observation --------------------------------------------------------------

    /**
     * Deleting a row returns how many rows it removed.
     *
     * `0` for an id that is not queued is the honest answer rather than an error, because a second
     * drain can legitimately find the row already gone, and the caller has no repair to make.
     */
    @Test
    fun `deleting a queued entry reports one row and deleting it again reports none`() = runTest {
        outboxDao.enqueue(row(enqueuedAt = 100L))

        assertEquals(1, outboxDao.deleteByEntryId("entry-1"))
        assertEquals(0, outboxDao.deleteByEntryId("entry-1"))
        assertEquals(0, outboxDao.oldestPending(10).size)
    }

    /**
     * `observePendingCount` tracks enqueues and removals.
     *
     * This is the read that decides whether to start a drain at all, so a count that only worked on a
     * freshly-created database would mean a sync that never runs in production.
     */
    @Test
    fun `the pending count follows enqueues and removals`() = runTest {
        assertEquals(0, outboxDao.observePendingCount().first())

        outboxDao.enqueue(row("entry-1", enqueuedAt = 100L))
        outboxDao.enqueue(row("entry-2", enqueuedAt = 200L))
        assertEquals(2, outboxDao.observePendingCount().first())

        // A collapse must not move the count: the work is still two entries' worth.
        outboxDao.enqueue(row("entry-2", enqueuedAt = 300L))
        assertEquals(2, outboxDao.observePendingCount().first())

        outboxDao.deleteByEntryId("entry-1")
        assertEquals(1, outboxDao.observePendingCount().first())
    }

    /**
     * An empty queue drains to nothing instead of failing.
     *
     * "Nothing pending" is the desired state. A caller that cannot tell it apart from "pending but
     * broken" will eventually report a healthy device as broken, which is how a sync indicator ends
     * up permanently alarming.
     */
    @Test
    fun `an empty queue reports no rows rather than failing`() = runTest {
        assertTrue(outboxDao.oldestPending(10).isEmpty())
    }

    // -- Fixture -------------------------------------------------------------------------------

    private fun row(
        entryId: String = "entry-1",
        op: SyncOutOp = SyncOutOp.UPSERT,
        enqueuedAt: Long,
        servings: Int = 2,
        kcal: Int = 200
    ) = SyncOutboxEntity(
        entryId = entryId,
        op = op.name,
        productBarcode = "7791234567890",
        productName = "Yogur natural",
        servings = servings,
        mealSlot = MealSlot.ALMUERZO,
        loggedAtEpochMillis = 1_772_419_200_000L,
        dayKey = "2026-03-02",
        kcal = kcal,
        carbsG = 30.0,
        proteinG = 10.0,
        fatG = 5.0,
        sugarsG = 4.0,
        fiberG = 2.0,
        sodiumG = 300.0,
        enqueuedAtEpochMillis = enqueuedAt
    )
}