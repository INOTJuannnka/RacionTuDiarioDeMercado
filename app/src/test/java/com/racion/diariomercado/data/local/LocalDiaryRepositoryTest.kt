package com.racion.diariomercado.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.local.dao.SyncOutboxDao
import com.racion.diariomercado.data.local.entity.DiaryEntryEntity
import com.racion.diariomercado.data.local.entity.FoodProductEntity
import com.racion.diariomercado.data.local.entity.NutritionGoalsEntity
import com.racion.diariomercado.data.local.entity.SyncOutOp
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.MealSlot
import com.racion.diariomercado.domain.model.Nutrition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * `LocalDiaryRepository` against a real in-memory ROOM database.
 *
 * ## What is actually under test here
 * Not SQL — `DiaryDaoTest` owns that. This file covers the three things the DAO cannot judge:
 *
 * 1. **The join is lossless.** The domain `DiaryEntry` carries a whole `FoodProduct`, and the
 *    entity stores no name. If the projection drops or renames a column, Room compiles fine and
 *    the meal row renders a blank name. Only an end-to-end read can catch that.
 * 2. **The weekly arithmetic**, where a wrong-but-plausible number still renders: an average over
 *    logged days instead of seven, a macro split by grams instead of energy, a streak anchored on
 *    Sunday instead of the last logged day. None of those crash; all of them lie.
 * 3. **The write path's FK contract.** `productBarcode` is RESTRICT, so writing an entry without
 *    its product is a hard failure at the exact moment a user scans something this phone has never
 *    seen. That is the common path, so it gets its own test.
 *
 * `allowMainThreadQueries()` matches `DiaryDaoTest`: Robolectric runs the body on the main thread
 * and an in-memory database gains nothing from an executor the test would have to pump.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDiaryRepositoryTest {

    private lateinit var db: RacionDatabase
    private lateinit var repository: LocalDiaryRepository

    /**
     * The outbox clock, under the test's control.
     *
     * A mutable `var` rather than a `var counter`: DB-7's queue ordering tests need to place rows
     * at *known* distances from each other, and `System.currentTimeMillis()` can return the same
     * millisecond for two consecutive calls, which would make an ordering assertion pass or fail by
     * timing luck.
     */
    private var nowEpochMillis = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        repository = LocalDiaryRepository(
            diaryDao = db.diaryDao(),
            catalogDao = db.catalogDao(),
            goalsDao = db.goalsDao(),
            syncOutboxDao = db.syncOutboxDao(),
            database = db,
            userId = TEST_USER_ID,
            now = { nowEpochMillis }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── observeDay ─────────────────────────────────────────────────

    /**
     * A day with no entries still emits, with a Spanish label, zeroes and the default goal.
     *
     * "Emits an empty day" is the contract, so the empty case is asserted with the same weight as
     * the populated one. A screen that receives `null` here crashes on a user's first ever launch.
     */
    @Test
    fun `observeDay emits an empty day when nothing is logged`() = runTest {
        val summary = repository.observeDay(TUE_25_AUG).first()

        assertEquals("Mar 25 Ago", summary.dateLabel)
        assertEquals(Nutrition(), summary.consumed)
        assertTrue(summary.entries.isEmpty())
        assertEquals(LocalDiaryRepository.DEFAULT_GOAL_KCAL, summary.goalKcal)
    }

    /** The label is the documented format, including the uppercase abbreviations. */
    @Test
    fun `observeDay labels the date in Spanish`() = runTest {
        seedEntry(day = TUE_25_AUG, kcal = 100)

        val summary = repository.observeDay(TUE_25_AUG).first()

        assertEquals("Mar 25 Ago", summary.dateLabel)
    }

    /** Totals come from the SQL aggregate, on all seven fields. */
    @Test
    fun `observeDay sums every nutrition column across entries`() = runTest {
        seedEntry(day = TUE_25_AUG, kcal = 100, carbs = 10.0, protein = 5.0, fat = 2.0)
        seedEntry(day = TUE_25_AUG, kcal = 250, carbs = 20.0, protein = 8.0, fat = 4.0)

        val consumed = repository.observeDay(TUE_25_AUG).first().consumed

        assertEquals(350, consumed.kcal)
        assertEquals(30.0, consumed.carbsG, TOLERANCE)
        assertEquals(13.0, consumed.proteinG, TOLERANCE)
        assertEquals(6.0, consumed.fatG, TOLERANCE)
    }

    /**
     * The entry's nutrition is the value **stored on the entry**, not
     * `per100g * servings` recomputed on read.
     *
     * The fixture is deliberately inconsistent — 100 kcal/100 g at 3 servings would be 300, but the
     * entry stores 250 — because that inconsistency is the whole point. Rebuilding the total from
     * the catalog re-rounds it and the number the user saw on the scan sheet stops matching the
     * number in the diary. This test fails the moment someone "simplifies" the mapping.
     */
    @Test
    fun `observeDay returns the stored entry nutrition instead of rescaling the catalog`() = runTest {
        seedEntry(
            day = TUE_25_AUG,
            kcal = 250,
            productKcalPer100g = 100,
            servings = 3
        )

        val entry = repository.observeDay(TUE_25_AUG).first().entries.single()

        assertEquals(250, entry.totalNutrition.kcal)
        assertEquals(100, entry.product.nutritionPer100g.kcal)
        assertEquals(3, entry.servings)
    }

    /** The join must carry the catalog fields the meal row renders. */
    @Test
    fun `observeDay rebuilds the full product through the join`() = runTest {
        seedEntry(
            day = TUE_25_AUG,
            kcal = 100,
            productName = "Yogur Natural",
            productBrand = "Ser dairy",
            productEmoji = "🥛",
            productNutriscoreGrade = "B"
        )

        val product = repository.observeDay(TUE_25_AUG).first().entries.single().product

        assertEquals("Yogur Natural", product.name)
        assertEquals("Ser dairy", product.brand)
        assertEquals("🥛", product.emoji)
        assertEquals("B", product.nutriscoreGrade)
        assertTrue(
            "expected the joined barcode to come from the catalog row, was ${product.barcode}",
            product.barcode.startsWith(BASE_BARCODE)
        )
    }

    /** A stored goals row wins over the default. */
    @Test
    fun `observeDay uses the stored kcal goal when present`() = runTest {
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = TEST_USER_ID, kcalPerDay = 2400))

        assertEquals(2400, repository.observeDay(TUE_25_AUG).first().goalKcal)
    }

    /**
     * The sentinel user id does not accidentally match someone else's goals.
     *
     * Worth pinning because the `userId` is currently a placeholder: if the lookup ever becomes
     * case-insensitive or unfiltered by accident, this is the test that notices.
     */
    @Test
    fun `observeDay ignores goals belonging to a different user`() = runTest {
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = "someone-else", kcalPerDay = 3000))

        assertEquals(
            LocalDiaryRepository.DEFAULT_GOAL_KCAL,
            repository.observeDay(TUE_25_AUG).first().goalKcal
        )
    }

    // ─── Writes ─────────────────────────────────────────────────────

    /**
     * `addEntry` persists the product **and** the entry.
     *
     * This is the FK regression test. `diary_entries.productBarcode` is RESTRICT, so an
     * implementation that upserts only the entry throws `SQLiteConstraintException` for every
     * product the phone had not cached yet — i.e. for most of what a user scans. The test seeds no
     * catalog row beforehand, which is what makes it bite.
     */
    @Test
    fun `addEntry writes the product row the foreign key requires`() = runTest {
        val result = repository.addEntry(domainEntry(productName = "Atún en lata"))

        assertTrue(result is AppResult.Success)
        val entry = repository.observeDay(TUE_25_AUG).first().entries.single()
        assertEquals("Atún en lata", entry.product.name)
    }

    /** `addEntry` derives the day key from the timestamp, not from "today". */
    @Test
    fun `addEntry files the entry under the day of its timestamp`() = runTest {
        repository.addEntry(
            domainEntry(
                day = TUE_25_AUG,
                productName = "Queso crema",
                loggedAt = TUE_25_AUG.atTime(13, 30).atZone(ZONE).toInstant().toEpochMilli()
            )
        )

        assertEquals(1, repository.observeDay(TUE_25_AUG).first().entries.size)
        assertTrue(repository.observeDay(WED_26_AUG).first().entries.isEmpty())
    }

    /** Entries come back oldest first, so the meal section reads top to bottom. */
    @Test
    fun `observeDay orders entries oldest first`() = runTest {
        seedEntry(day = TUE_25_AUG, kcal = 100, loggedAtHour = 8, id = "b")
        seedEntry(day = TUE_25_AUG, kcal = 100, loggedAtHour = 13, id = "c")
        seedEntry(day = TUE_25_AUG, kcal = 100, loggedAtHour = 20, id = "a")

        val ids = repository.observeDay(TUE_25_AUG).first().entries.map { it.id }

        assertEquals(listOf("b", "c", "a"), ids)
    }

    /**
     * Deleting an entry that is already gone is a **success**.
     *
     * Idempotent by design: an offline queue legitimately retries a delete after the row was
     * removed, and reporting `NotFound` would make a correct retry look like a failure.
     */
    @Test
    fun `deleteEntry treats an unknown id as success`() = runTest {
        val result = repository.deleteEntry("does-not-exist")

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `deleteEntry removes the row and the totals follow`() = runTest {
        seedEntry(day = TUE_25_AUG, kcal = 100, id = "keep")
        seedEntry(day = TUE_25_AUG, kcal = 400, id = "drop")

        assertTrue(repository.deleteEntry("drop") is AppResult.Success)

        val summary = repository.observeDay(TUE_25_AUG).first()
        assertEquals(100, summary.consumed.kcal)
        assertEquals(listOf("keep"), summary.entries.map { it.id })
    }

    /** Recent scans are newest first and capped at `limit`. */
    @Test
    fun `recentScans returns newest first up to the limit`() = runTest {
        seedEntry(day = TUE_25_AUG, kcal = 100, loggedAtHour = 8, id = "old")
        seedEntry(day = TUE_25_AUG, kcal = 100, loggedAtHour = 13, id = "mid")
        seedEntry(day = WED_26_AUG, kcal = 100, loggedAtHour = 9, id = "new")

        val result = repository.recentScans(limit = 2)

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("new", "mid"), (result as AppResult.Success).data.map { it.id })
    }

    // ─── DB-7: what each write owes the server ────────────────────────────────────────

    /**
     * `addEntry` queues an UPSERT carrying the entry, in the same transaction.
     *
     * The outbox row is asserted on every field that has to agree with `diary_entries`: an upsert
     * that reported a different day key or a different kcal would make the server's precomputed day
     * total wrong in a way no local screen can show.
     */
    @Test
    fun `addEntry queues an upsert with the entry's own day key and nutrition`() = runTest {
        val entry = domainEntry(day = TUE_25_AUG, productName = "Yogur")

        assertTrue(repository.addEntry(entry) is AppResult.Success)

        val queued = db.syncOutboxDao().oldestPending(10).single()
        assertEquals(entry.id, queued.entryId)
        assertEquals(SyncOutOp.UPSERT, queued.operation)
        assertEquals(TUE_25_AUG.toString(), queued.dayKey)
        assertEquals(entry.product.name, queued.productName)
        assertEquals(entry.totalNutrition.kcal, queued.kcal)
        assertEquals(entry.totalNutrition.proteinG, queued.proteinG, TOLERANCE)
        assertEquals("the injected clock is what the row must record", nowEpochMillis, queued.enqueuedAtEpochMillis)
    }

    /**
     * The queue row does not exist without the entry row.
     *
     * This is the transactional claim, and it is observable in only one direction from a test: a
     * repository that enqueued *outside* the transaction would look identical on the happy path and
     * differ only when one of the two writes fails. The cheapest honest check of that is that a
     * write which cannot complete leaves no trace at all — see `a rejected addEntry queues nothing`.
     */
    @Test
    fun `addEntry leaves exactly one queued row per entry`() = runTest {
        repository.addEntry(domainEntry(productName = "Uno"))
        repository.addEntry(domainEntry(productName = "Dos"))

        val queued = db.syncOutboxDao().oldestPending(10)
        assertEquals(2, queued.size)
        assertEquals(2, queued.map { it.entryId }.toSet().size)
    }

    /**
     * A failed outbox enqueue rolls the entry back — nothing local, nothing queued.
     *
     * This is the transactional claim, and it is only observable when one of the three writes in
     * `addEntry` fails. The failure is injected at the DAO rather than by provoking a constraint
     * violation, because `addEntry` *upserts*: re-adding the same entry id succeeds, so there is no
     * natural violation to hang the test on.
     *
     * The bug it catches is enqueueing outside the transaction. In that version the entry is written,
     * then the enqueue throws, and the user is left with an entry the server will never hear about —
     * a state that looks perfectly healthy everywhere: the diary renders, the totals are right, and
     * nothing in the app says a change is unsynced.
     */
    @Test
    fun `a failed outbox enqueue rolls the whole add back`() = runTest {
        val failing = object : SyncOutboxDao by db.syncOutboxDao() {
            override suspend fun enqueue(row: SyncOutboxEntity) {
                throw SQLiteConstraintException("simulated outbox failure")
            }
        }
        val repositoryWithBrokenQueue = LocalDiaryRepository(
            diaryDao = db.diaryDao(),
            catalogDao = db.catalogDao(),
            goalsDao = db.goalsDao(),
            syncOutboxDao = failing,
            database = db,
            userId = TEST_USER_ID,
            now = { nowEpochMillis }
        )
        val entry = domainEntry(day = TUE_25_AUG, productName = "Yogur")

        assertTrue("the enqueue failure must surface as a Failure", repositoryWithBrokenQueue.addEntry(entry) is AppResult.Failure)

        assertEquals(
            "the entry must not survive a transaction that could not be queued",
            0,
            db.diaryDao().dayTotals(TUE_25_AUG.toString()).first().entryCount
        )
        assertNull("the entry row itself must have rolled back", db.diaryDao().withProductById(entry.id))
        assertEquals(0, db.syncOutboxDao().oldestPending(10).size)
    }

    /**
     * `deleteEntry` queues a DELETE carrying the row's nutrition — read before the row is gone.
     *
     * The numbers are the whole design. A DELETE row that did not carry them could not decrement the
     * server's precomputed day total, which is why D2 is a table and not a flag column: after the
     * local `DELETE` runs, this information exists nowhere else on the device.
     */
    @Test
    fun `deleteEntry queues a delete carrying the deleted row's nutrition`() = runTest {
        seedEntry(day = TUE_25_AUG, kcal = 100, carbs = 12.0, id = "doomed")

        assertTrue(repository.deleteEntry("doomed") is AppResult.Success)

        val queued = db.syncOutboxDao().oldestPending(10).single()
        assertEquals("doomed", queued.entryId)
        assertEquals(SyncOutOp.DELETE, queued.operation)
        assertEquals(100, queued.kcal)
        assertEquals(12.0, queued.carbsG, TOLERANCE)
        assertEquals(TUE_25_AUG.toString(), queued.dayKey)
    }

    /**
     * Deleting an id that is not there enqueues nothing.
     *
     * The idempotent-retry case: an offline retry can arrive after the row was already removed, and
     * queuing a DELETE built from invented values would subtract a phantom entry from the server's
     * day total. Asserting the queue stays empty is what makes "idempotent" mean "no side effect",
     * not merely "returns success".
     */
    @Test
    fun `deleting an unknown id queues nothing`() = runTest {
        assertTrue(repository.deleteEntry("does-not-exist") is AppResult.Success)

        assertEquals(0, db.syncOutboxDao().oldestPending(10).size)
    }

    /**
     * Add then remove leaves a DELETE queued, not an UPSERT and not two rows.
     *
     * This is the repository's half of the UPSERT→DELETE collapse rule, asserted end to end from the
     * domain entry down to the queue. The other half — that the primary key collapses regardless of
     * who wrote the rows — is `SyncOutboxDaoTest`'s job.
     */
    @Test
    fun `adding then deleting an entry leaves a single delete queued`() = runTest {
        val entry = domainEntry(day = TUE_25_AUG, productName = "Yogur")
        repository.addEntry(entry)
        nowEpochMillis += 1_000L

        assertTrue(repository.deleteEntry(entry.id) is AppResult.Success)

        val queued = db.syncOutboxDao().oldestPending(10)
        assertEquals(1, queued.size)
        assertEquals(
            "collapsing to an upsert here would resurrect the entry on the server",
            SyncOutOp.DELETE,
            queued.single().operation
        )
    }

    /**
     * Queue order is the order the local writes happened in.
     *
     * `seedEntry` writes straight through the DAOs — it reproduces what the repository does, so it
     * deliberately does not queue. These two are therefore driven through `addEntry`, which is the
     * path that enqueues, with the injected clock moved between them.
     */
    @Test
    fun `queued entries drain oldest first`() = runTest {
        repository.addEntry(domainEntry(productName = "Primero"))

        nowEpochMillis += 1_000L
        repository.addEntry(domainEntry(productName = "Segundo"))

        val queued = db.syncOutboxDao().oldestPending(10)
        assertEquals(2, queued.size)
        assertEquals(1_700_000_000_000L, queued.first().enqueuedAtEpochMillis)
        assertEquals(1_700_000_001_000L, queued.last().enqueuedAtEpochMillis)
    }

    // ─── observeWeek ────────────────────────────────────────────────

    /**
     * Seven bars, always — including zeroes for days with no rows.
     *
     * A missing bar would silently rescale the chart's x-axis, so the mapping layer fills the gaps
     * with real zeroes rather than emitting only the days that happen to have data.
     */
    @Test
    fun `observeWeek always returns seven bars with zeroes for empty days`() = runTest {
        val report = repository.observeWeek(MON_24_AUG).first()

        assertEquals(7, report.days.size)
        assertEquals(listOf("L", "M", "X", "J", "V", "S", "D"), report.days.map { it.label })
        assertTrue(report.days.all { it.kcal == 0 })
    }

    @Test
    fun `observeWeek labels the range and shows the totals per bar`() = runTest {
        seedEntry(day = MON_24_AUG, kcal = 1800)
        seedEntry(day = WED_26_AUG, kcal = 2100)

        val report = repository.observeWeek(MON_24_AUG).first()

        assertEquals("24–30 AGO", report.weekRangeLabel)
        assertEquals(1800, report.days[0].kcal)
        assertEquals(2100, report.days[2].kcal)
        assertEquals(0, report.days[6].kcal)
    }

    /**
     * A range crossing a month boundary must name both months or it is unverifiable.
     *
     * "SEPT", not "SEP": that is the Spanish CLDR abbreviation the JVM actually produces for
     * September, and the test asserts the real string rather than the tidier one I expected. A
     * label test that asserts a hand-written guess teaches you nothing when it passes.
     */
    @Test
    fun `observeWeek names both months when the range crosses one`() = runTest {
        assertEquals(
            "31 AGO – 6 SEPT",
            repository.observeWeek(LocalDate.of(2026, 8, 31)).first().weekRangeLabel
        )
    }

    /**
     * The average divides by **seven**, not by the number of logged days.
     *
     * 3900 kcal over two logged days is 1950/day against a 1900 goal — a full bar. Divided by the
     * logged days it would still be 1950, but divided by nothing sensible it reads as starving.
     * The case that matters: 700 kcal logged on one day of a fresh week must report 100/day, not
     * 700/day, otherwise the first day of logging looks like a feast.
     */
    @Test
    fun `observeWeek averages over the whole week not just logged days`() = runTest {
        seedEntry(day = MON_24_AUG, kcal = 700)

        assertEquals(100, repository.observeWeek(MON_24_AUG).first().averageKcalPerDay)
    }

    /** The best day is the highest bar, and only when the week has something in it. */
    @Test
    fun `observeWeek highlights the best day`() = runTest {
        seedEntry(day = MON_24_AUG, kcal = 1800)
        seedEntry(day = FRI_28_AUG, kcal = 2400)

        val report = repository.observeWeek(MON_24_AUG).first()

        assertEquals("V", report.bestDayLabel)
        assertEquals(listOf(4), report.days.withIndex().filter { it.value.isHighlighted }.map { it.index })
    }

    /**
     * An empty week has no best day.
     *
     * Highlighting the first zero bar would tell the user their best day was Monday when they
     * logged nothing at all.
     */
    @Test
    fun `observeWeek reports no best day when the week is empty`() = runTest {
        val report = repository.observeWeek(MON_24_AUG).first()

        assertNull(report.bestDayLabel)
        assertTrue(report.days.none { it.isHighlighted })
    }

    /**
     * The streak is anchored on the last logged day, so a week that simply ended does not read as
     * zero.
     */
    @Test
    fun `observeWeek counts the streak back from the last logged day`() = runTest {
        seedEntry(day = MON_24_AUG, kcal = 100)
        seedEntry(day = TUE_25_AUG, kcal = 100)
        seedEntry(day = WED_26_AUG, kcal = 100)

        assertEquals(3, repository.observeWeek(MON_24_AUG).first().activeStreakDays)
    }

    /** A gap ends the run: three days with a hole in the middle is not a streak of four. */
    @Test
    fun `observeWeek stops the streak at the first gap`() = runTest {
        seedEntry(day = MON_24_AUG, kcal = 100)
        seedEntry(day = WED_26_AUG, kcal = 100)
        seedEntry(day = THU_27_AUG, kcal = 100)

        assertEquals(2, repository.observeWeek(MON_24_AUG).first().activeStreakDays)
    }

    @Test
    fun `observeWeek reports zero streak when nothing is logged`() = runTest {
        assertEquals(0, repository.observeWeek(MON_24_AUG).first().activeStreakDays)
    }

    /**
     * The macro split is by **energy**, not by grams.
     *
     * 40 g of carbs and 40 g of fat are 160 and 360 kcal. By mass that reads 50/50; by energy it is
     * 30/69 — 160/520 = 30.7% and 360/520 = 69.2%, truncated because `MacroSplit` documents that
     * the three percentages are not normalised to 100.
     *
     * A gram-based split would return 50/0/50 here, so the assertion does fail on the wrong
     * implementation rather than just agreeing with it.
     */
    @Test
    fun `observeWeek splits macros by energy rather than by mass`() = runTest {
        seedEntry(day = MON_24_AUG, kcal = 700, carbs = 40.0, protein = 0.0, fat = 40.0)

        val split = repository.observeWeek(MON_24_AUG).first().macroSplit

        assertEquals(30, split.carbsPct)
        assertEquals(0, split.proteinPct)
        assertEquals(69, split.fatPct)
    }

    /** An empty week must not invent a confident-looking default donut. */
    @Test
    fun `observeWeek reports a zero macro split for an empty week`() = runTest {
        val split = repository.observeWeek(MON_24_AUG).first().macroSplit

        assertEquals(0, split.carbsPct)
        assertEquals(0, split.proteinPct)
        assertEquals(0, split.fatPct)
    }

    @Test
    fun `observeWeek carries the stored goal through`() = runTest {
        db.goalsDao().saveGoals(NutritionGoalsEntity(userId = TEST_USER_ID, kcalPerDay = 2100))

        assertEquals(2100, repository.observeWeek(MON_24_AUG).first().goalKcal)
    }

    /** A week boundary must not leak the neighbouring week in either direction. */
    @Test
    fun `observeWeek excludes days outside the requested range`() = runTest {
        seedEntry(day = SUN_23_AUG, kcal = 999) // the Sunday before this Monday
        seedEntry(day = MON_31_AUG, kcal = 888) // the Monday after this Sunday

        val report = repository.observeWeek(MON_24_AUG).first()

        assertEquals(0, report.days.sumOf { it.kcal })
        assertNotNull(report.weekRangeLabel)
    }

    // ─── Fixtures ───────────────────────────────────────────────────

    /** Seeds one product + entry pair, reproducing the FK order `addEntry` must follow. */
    private suspend fun seedEntry(
        day: LocalDate,
        kcal: Int,
        carbs: Double = 0.0,
        protein: Double = 0.0,
        fat: Double = 0.0,
        id: String = "entry-$kcal-${day.toString()}",
        loggedAtHour: Int = 12,
        servings: Int = 1,
        productName: String = "Producto de prueba",
        productBrand: String? = "Marca",
        productEmoji: String = "🍎",
        productNutriscoreGrade: String? = "A",
        productKcalPer100g: Int = kcal
    ) {
        db.catalogDao().upsert(
            FoodProductEntity(
                barcode = "$BASE_BARCODE-$id",
                name = productName,
                brand = productBrand,
                quantityLabel = "100 g",
                servingGrams = 100.0,
                kcalPer100g = productKcalPer100g,
                carbsPer100g = carbs,
                proteinPer100g = protein,
                fatPer100g = fat,
                sugarsPer100g = 0.0,
                fiberPer100g = 0.0,
                sodiumPer100g = 0.0,
                imageUrl = null,
                nutriscoreGrade = productNutriscoreGrade,
                categories = listOf("test"),
                ingredientsText = null,
                emoji = productEmoji
            )
        )
        db.diaryDao().upsert(
            DiaryEntryEntity(
                id = id,
                productBarcode = "$BASE_BARCODE-$id",
                servings = servings,
                mealSlot = MealSlot.ALMUERZO,
                loggedAtEpochMillis = day.atTime(loggedAtHour, 0).atZone(ZONE).toInstant().toEpochMilli(),
                dayKey = day.toString(),
                kcal = kcal,
                carbsG = carbs,
                proteinG = protein,
                fatG = fat,
                sugarsG = 0.0,
                fiberG = 0.0,
                sodiumG = 0.0
            )
        )
    }

    /** The domain object `addEntry` receives, with a consistent `FoodProduct`. */
    private fun domainEntry(
        day: LocalDate = TUE_25_AUG,
        productName: String = "Producto de prueba",
        loggedAt: Long = day.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli()
    ) = DiaryEntry(
        id = "added-${productName.hashCode()}",
        product = FoodProduct(
            barcode = "$BASE_BARCODE-added-${productName.hashCode()}",
            name = productName,
            brand = "Marca",
            quantityLabel = "100 g",
            servingGrams = 100.0,
            nutritionPer100g = Nutrition(kcal = 120, carbsG = 10.0, proteinG = 4.0, fatG = 2.0),
            imageUrl = null,
            nutriscoreGrade = "A",
            categories = listOf("test"),
            ingredientsText = null,
            emoji = "🍎"
        ),
        servings = 1,
        mealSlot = MealSlot.ALMUERZO,
        loggedAtEpochMillis = loggedAt,
        totalNutrition = Nutrition(kcal = 120, carbsG = 10.0, proteinG = 4.0, fatG = 2.0)
    )

    private companion object {
        const val TEST_USER_ID = "test-user"
        const val BASE_BARCODE = "7790000000000"
        const val TOLERANCE = 1e-9

        val ZONE = java.time.ZoneId.systemDefault()

        val MON_24_AUG = LocalDate.of(2026, 8, 24)
        val TUE_25_AUG = LocalDate.of(2026, 8, 25)
        val WED_26_AUG = LocalDate.of(2026, 8, 26)
        val THU_27_AUG = LocalDate.of(2026, 8, 27)
        val FRI_28_AUG = LocalDate.of(2026, 8, 28)
        val SUN_23_AUG = LocalDate.of(2026, 8, 23)
        val MON_31_AUG = LocalDate.of(2026, 8, 31)
    }
}
