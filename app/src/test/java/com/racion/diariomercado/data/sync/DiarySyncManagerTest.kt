package com.racion.diariomercado.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.local.RacionDatabase
import com.racion.diariomercado.data.local.dao.SyncOutboxDao
import com.racion.diariomercado.data.local.entity.SyncOutOp
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.domain.model.MealSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The drain's failure and ordering policy, against a fake transport and a real queue.
 *
 * ## Why the transport is a fake and not a mock
 * Design D5 exists so this file can exist. `SyncTransport` has two methods; a hand-written fake
 * records what it was asked to send and can be told to fail on the Nth call, which is the only way
 * to state "stop at the first failure and leave the rest queued" as a fact about observable
 * behaviour. A mocking framework would assert on the same interactions while coupling the test to
 * invocation syntax and making the payload assertions depend on its matcher vocabulary.
 *
 * The fake is **not** a mock of the queue: `sync_outbox` is a real Room database, because the
 * contract this class owns — delete only what the server confirmed — is a contract about the queue's
 * *contents*, and a mocked DAO could not catch a drain that deleted a row it had not uploaded.
 */
@RunWith(RobolectricTestRunner::class)
class DiarySyncManagerTest {

    private lateinit var db: RacionDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var transport: FakeSyncTransport
    private lateinit var manager: DiarySyncManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        outboxDao = db.syncOutboxDao()
        transport = FakeSyncTransport()
        manager = DiarySyncManager(outboxDao, transport)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- The happy path -------------------------------------------------------------------------

    /**
     * A full batch is uploaded oldest-first and every confirmed row is removed.
     *
     * Order is asserted, not just membership: the drain's contract is that pending work goes out in
     * the order it was queued, and a transport that received two entries in the wrong order can
     * produce a server state that is wrong even though both documents end up correct.
     */
    @Test
    fun `drain uploads oldest first and removes only confirmed rows`() = runTest {
        enqueue("entry-b", SyncOutOp.UPSERT, enqueuedAt = 200L)
        enqueue("entry-a", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("entry-c", SyncOutOp.DELETE, enqueuedAt = 300L)

        assertEquals(AppResult.Success(3), manager.drain())

        assertEquals(listOf("entry-a", "entry-b", "entry-c"), transport.sent)
        assertEquals(SyncOutOp.UPSERT, transport.operationFor("entry-a"))
        assertEquals(SyncOutOp.DELETE, transport.operationFor("entry-c"))
        assertEquals(0, outboxDao.observePendingCount().first())
    }

    /**
     * The two operations reach the two transport methods, and never each other's.
     *
     * Wiring `DELETE` to `upsertEntry` would leave the entry on the server forever while the local
     * queue reported success — a ghost entry with no local row to correct it, which is the failure
     * the UPSERT→DELETE collapse rule exists to prevent on the other side of the queue.
     */
    @Test
    fun `upsert and delete rows reach their matching transport operations`() = runTest {
        enqueue("to-upsert", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("to-delete", SyncOutOp.DELETE, enqueuedAt = 200L)

        manager.drain()

        assertEquals(listOf("to-upsert"), transport.upserted)
        assertEquals(listOf("to-delete"), transport.deleted)
    }

    /**
     * An empty queue is a success of zero, not a failure.
     *
     * "Nothing to do" is the healthy state, and a caller that cannot distinguish it from a broken
     * sync reports a working device as broken.
     */
    @Test
    fun `draining an empty queue succeeds with zero`() = runTest {
        assertEquals(AppResult.Success(0), manager.drain())
        assertTrue(transport.sent.isEmpty())
    }

    /**
     * The batch is bounded by `limit`, and the rows beyond it stay queued.
     *
     * This is the drain's battery contract: a backlog that grew during a week without signal must
     * not turn one trigger into an unbounded network session.
     */
    @Test
    fun `drain stops at its limit and leaves the rest queued`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("entry-2", SyncOutOp.UPSERT, enqueuedAt = 200L)
        enqueue("entry-3", SyncOutOp.UPSERT, enqueuedAt = 300L)

        assertEquals(AppResult.Success(2), manager.drain(limit = 2))

        assertEquals(listOf("entry-1", "entry-2"), transport.sent)
        assertEquals(listOf("entry-3"), outboxDao.oldestPending(10).map { it.entryId })
    }

    /**
     * A non-positive `limit` throws instead of draining everything.
     *
     * SQLite reads a negative `LIMIT` as *no* limit, so a `-1` typo would silently become an
     * unbounded upload of the entire queue — on a phone, on whatever trigger happened to fire. A
     * programming error that would silently do the wrong thing is worth an exception.
     */
    @Test
    fun `a non-positive limit is rejected`() {
        val thrown = runCatching { runBlocking { manager.drain(limit = 0) } }

        assertTrue(
            "expected an IllegalArgumentException for limit = 0, got ${thrown.exceptionOrNull()}",
            thrown.exceptionOrNull() is IllegalArgumentException
        )
    }

    // -- Failure semantics ---------------------------------------------------------------------

    /**
     * The first failure stops the drain, and its error is reported unchanged.
     *
     * The error passes through untouched because the caller — not the manager — owns the backoff
     * policy. A manager that mapped `Network` to its own "retry later" would take that decision away
     * from whoever knows whether the user is on wifi or mobile data.
     */
    @Test
    fun `a failure stops the drain and is returned unchanged`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("entry-2", SyncOutOp.UPSERT, enqueuedAt = 200L)
        enqueue("entry-3", SyncOutOp.UPSERT, enqueuedAt = 300L)
        transport.failFromCall = 2
        transport.failWith = AppError.Network

        assertEquals(AppResult.Failure(AppError.Network), manager.drain())

        assertEquals(
            "the drain must stop on the failing call and not attempt the third",
            2,
            transport.callCount
        )
    }

    /**
     * The failed row and every row after it stay queued.
     *
     * This is the assertion that makes the previous one mean something. A drain that stopped on
     * failure but deleted the row anyway would look identical from the return value and would lose
     * the change — the one failure this whole feature exists to prevent.
     */
    @Test
    fun `a failed row is not deleted, and neither are the rows behind it`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("entry-2", SyncOutOp.UPSERT, enqueuedAt = 200L)
        enqueue("entry-3", SyncOutOp.UPSERT, enqueuedAt = 300L)
        transport.failFromCall = 2

        manager.drain()

        assertEquals(listOf("entry-2", "entry-3"), outboxDao.oldestPending(10).map { it.entryId })
    }

    /**
     * Rows confirmed before the failure **are** removed.
     *
     * The mirror image of the previous test, and the reason the manager is written as a loop rather
     * than as "upload all, then delete all": the rows the server accepted are gone for good, and
     * re-uploading them is safe only because the transport is idempotent — not free, and not
     * something to impose on every other call.
     */
    @Test
    fun `rows confirmed before the failure are removed`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("entry-2", SyncOutOp.UPSERT, enqueuedAt = 200L)
        enqueue("entry-3", SyncOutOp.UPSERT, enqueuedAt = 300L)
        transport.failFromCall = 3
        transport.failWith = AppError.RateLimited

        assertEquals(AppResult.Failure(AppError.RateLimited), manager.drain())

        assertEquals(listOf("entry-3"), outboxDao.oldestPending(10).map { it.entryId })
    }

    /**
     * A non-retryable error is reported with its own type, not flattened into `Network`.
     *
     * Retrying a `Server(500)` on a backoff and retrying a `Network` are different policies, and a
     * manager that collapsed them would remove the caller's ability to choose.
     */
    @Test
    fun `a server error is not flattened into a network error`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        transport.failFromCall = 1
        transport.failWith = AppError.Server(code = 500, message = null)

        assertEquals(
            AppResult.Failure(AppError.Server(code = 500, message = null)),
            manager.drain()
        )
    }

    /**
     * A failed delete does not delete the row.
     *
     * Worth separating from the upsert case because this is the arithmetic case: the local row is
     * already gone, so the only record that this entry must be removed from the server *is* that
     * queue row. Discarding it because the network blinked would leave an entry on the server that no
     * local operation will ever correct.
     */
    @Test
    fun `a failed delete keeps the queue row that records the pending deletion`() = runTest {
        enqueue("entry-1", SyncOutOp.DELETE, enqueuedAt = 100L)
        transport.failFromCall = 1

        manager.drain()

        val pending = outboxDao.oldestPending(10)
        assertEquals(1, pending.size)
        assertEquals(SyncOutOp.DELETE, pending.single().operation)
    }

    // -- Idempotence and payload ---------------------------------------------------------------

    /**
     * A drained-then-drained queue sends nothing the second time.
     *
     * The plain reading of "idempotent over `entryId`": once the server has confirmed a row and the
     * row is gone, a repeat trigger must be a no-op rather than a duplicate upload.
     */
    @Test
    fun `a second drain of a drained queue does nothing`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        enqueue("entry-2", SyncOutOp.DELETE, enqueuedAt = 200L)

        assertEquals(AppResult.Success(2), manager.drain())
        val callsAfterFirst = transport.callCount

        assertEquals(AppResult.Success(0), manager.drain())
        assertEquals(
            "the second drain must not call the transport at all",
            callsAfterFirst,
            transport.callCount
        )
    }

    /**
     * The payload carries the entry and its day key, and never the queue timestamp.
     *
     * The crash-between-upload-and-delete path re-sends the same row, so the payload a transport
     * receives on the retry has to carry the same values — including the entry's own `dayKey`, which
     * is what tells the server which day document to decrement.
     *
     * `enqueuedAtEpochMillis` has no counterpart in [SyncEntryPayload] at all, and that is enforced
     * by the type rather than by this test: there is nowhere to put it. All this test can observe is
     * that the values that *do* exist came through, and that the queue row is gone afterwards so the
     * timestamp left the device with it.
     */
    @Test
    fun `the payload carries the entry and the day key`() = runTest {
        enqueue("entry-1", SyncOutOp.DELETE, enqueuedAt = 999_999L)

        manager.drain()

        val payload = transport.payloads.single()
        assertEquals("entry-1", payload.entryId)
        assertEquals(DAY_KEY, payload.dayKey)
        assertEquals(200, payload.kcal)
        assertEquals(MealSlot.ALMUERZO, payload.mealSlot)
        assertEquals("the queue timestamp is bookkeeping and has no field here", DAY_KEY, payload.dayKey)
        assertEquals(
            "the queue row leaves with the confirmed upload",
            0,
            outboxDao.observePendingCount().first()
        )
    }

    /**
     * The batch is a snapshot: a row enqueued *during* the drain waits for the next one.
     *
     * Reading one row per iteration instead would let a write made between two uploads join the
     * batch it did not belong to, and on a device that syncs on every resume that is a normal
     * occurrence rather than a race nobody will ever see.
     */
    @Test
    fun `a row enqueued during the drain is not picked up by it`() = runTest {
        enqueue("entry-1", SyncOutOp.UPSERT, enqueuedAt = 100L)
        transport.onBeforeCall = { call ->
            if (call == 1) enqueue("entry-2", SyncOutOp.UPSERT, enqueuedAt = 200L)
        }

        assertEquals(AppResult.Success(1), manager.drain())

        assertEquals(listOf("entry-1"), transport.sent)
        assertEquals(listOf("entry-2"), outboxDao.oldestPending(10).map { it.entryId })
    }

    // -- Fixtures and fake ----------------------------------------------------------------------

    private suspend fun enqueue(entryId: String, op: SyncOutOp, enqueuedAt: Long) {
        outboxDao.enqueue(
            SyncOutboxEntity(
                entryId = entryId,
                op = op.name,
                productBarcode = "7791234567890",
                productName = "Yogur natural",
                servings = 2,
                mealSlot = MealSlot.ALMUERZO,
                loggedAtEpochMillis = 1_772_419_200_000L,
                dayKey = DAY_KEY,
                kcal = 200,
                carbsG = 30.0,
                proteinG = 10.0,
                fatG = 5.0,
                sugarsG = 4.0,
                fiberG = 2.0,
                sodiumG = 300.0,
                enqueuedAtEpochMillis = enqueuedAt
            )
        )
    }

    /**
     * Records every call in order, can be told to fail from the Nth one, and can act mid-drain.
     *
     * `sent` is the single ordered list most assertions use; `upserted`/`deleted` exist so the
     * operation-routing test can prove the two methods were not swapped. `onBeforeCall` is a hook
     * rather than something more elaborate because the only mid-drain event this class needs to
     * simulate is "a row appeared".
     */
    private class FakeSyncTransport : SyncTransport {

        /** Every entry successfully handed to the transport, in call order. */
        val sent = mutableListOf<String>()
        val upserted = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val payloads = mutableListOf<SyncEntryPayload>()
        private val operations = mutableMapOf<String, SyncOutOp>()

        var callCount = 0
            private set

        /** 1-based call index from which every operation fails. */
        var failFromCall: Int? = null
        var failWith: AppError = AppError.Network

        var onBeforeCall: (suspend (Int) -> Unit)? = null

        override suspend fun upsertEntry(payload: SyncEntryPayload): AppResult<Unit> =
            record(payload, SyncOutOp.UPSERT)

        override suspend fun deleteEntry(payload: SyncEntryPayload): AppResult<Unit> =
            record(payload, SyncOutOp.DELETE)

        private suspend fun record(payload: SyncEntryPayload, op: SyncOutOp): AppResult<Unit> {
            callCount++
            onBeforeCall?.invoke(callCount)

val failAt = failFromCall
if (failAt != null && callCount >= failAt) {
 return AppResult.Failure(failWith)
}

            sent += payload.entryId
            when (op) {
                SyncOutOp.UPSERT -> upserted += payload.entryId
                SyncOutOp.DELETE -> deleted += payload.entryId
            }
            payloads += payload
            operations[payload.entryId] = op
            return AppResult.Success(Unit)
        }

        fun operationFor(entryId: String): SyncOutOp? = operations[entryId]
    }

    private companion object {
        const val DAY_KEY = "2026-03-02"
    }
}