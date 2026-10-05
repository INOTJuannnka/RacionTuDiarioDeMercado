package com.racion.diariomercado.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.racion.diariomercado.data.local.dao.DiaryDao
import com.racion.diariomercado.data.local.entity.DiaryEntryEntity
import com.racion.diariomercado.data.local.entity.FoodProductEntity
import com.racion.diariomercado.domain.model.MealSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO behaviour against a real in-memory Room database.
 *
 * ## Why the aggregate tests assert on **all seven** fields, not just `kcal`
 * A `SUM` query that sums `kcal` correctly and silently returns zero for `sodiumG` — because the
 * column was renamed, or aliased wrong, or left out of the projection — passes any test that only
 * looks at calories. The UI would show correct energy next to a permanently flat protein bar, and
 * nothing would crash. Seven asserts are the cheapest insurance against a partial aggregate.
 *
 * `allowMainThreadQueries()` is on because Robolectric runs the test body on the main thread and
 * Room refuses to touch SQLite there by default; the alternative is an executor the test has to
 * pump, which buys nothing for an in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
class DiaryDaoTest {

    private lateinit var db: RacionDatabase
    private lateinit var diaryDao: DiaryDao

    private val productBarcode = "7791234567890"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        diaryDao = db.diaryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Day totals via SUM (DB-4) ----------------------------------------------------------

    /**
     * The core of DB-4: three entries in one day aggregate to their arithmetic sum, on all seven
     * nutrition columns.
     *
     * The fixture values are all exactly representable as IEEE-754 doubles, so the assertion is
     * exact rather than within a tolerance. A tolerance here would hide the failure this test
     * exists to catch: a column that aggregates to the wrong magnitude.
     */
    @Test
    fun dayTotalsAreTheSqlSumOfEveryNutritionField() = runTest {
        seedCatalog()
        diaryDao.upsert(
            entry(id = "e1", dayKey = DAY, kcal = 200, carbsG = 30.0, proteinG = 10.0,
                fatG = 5.0, sugarsG = 4.0, fiberG = 2.0, sodiumG = 300.0)
        )
        diaryDao.upsert(
            entry(id = "e2", dayKey = DAY, kcal = 150, carbsG = 20.0, proteinG = 8.0,
                fatG = 6.0, sugarsG = 3.0, fiberG = 1.5, sodiumG = 250.0)
        )
        diaryDao.upsert(
            entry(id = "e3", dayKey = DAY, kcal = 90, carbsG = 11.0, proteinG = 3.0,
                fatG = 2.0, sugarsG = 1.0, fiberG = 0.5, sodiumG = 90.0)
        )

        val totals = diaryDao.dayTotals(DAY).first()

        assertEquals(440, totals.kcal)
        assertEquals(61.0, totals.carbsG, TOLERANCE)
        assertEquals(21.0, totals.proteinG, TOLERANCE)
        assertEquals(13.0, totals.fatG, TOLERANCE)
        assertEquals(8.0, totals.sugarsG, TOLERANCE)
        assertEquals(4.0, totals.fiberG, TOLERANCE)
        assertEquals(640.0, totals.sodiumG, TOLERANCE)
        assertEquals(3, totals.entryCount)
    }

    /**
     * An empty day is a row of zeroes — not a missing emission, not a null, not a crash.
     *
     * `SUM` over zero rows returns `NULL`, so without `COALESCE` this is the query that breaks
     * first on a fresh install: the first thing a new user does is open a day they have not eaten
     * on. There is a populated neighbouring day on purpose, so a "no rows anywhere" pass cannot
     * masquerade as the real thing.
     */
    @Test
    fun anEmptyDayYieldsZerosRatherThanNull() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "e1", dayKey = DAY, kcal = 200, carbsG = 30.0, proteinG = 10.0))

        val totals = diaryDao.dayTotals(EMPTY_DAY).first()

        assertEquals(0, totals.kcal)
        assertEquals(0.0, totals.carbsG, TOLERANCE)
        assertEquals(0.0, totals.proteinG, TOLERANCE)
        assertEquals(0.0, totals.fatG, TOLERANCE)
        assertEquals(0.0, totals.sugarsG, TOLERANCE)
        assertEquals(0.0, totals.fiberG, TOLERANCE)
        assertEquals(0.0, totals.sodiumG, TOLERANCE)
        assertEquals(0, totals.entryCount)
    }

    /** A database with no entries at all still answers the aggregate query with one row. */
    @Test
    fun anUntouchedDatabaseStillAnswersTheAggregateQuery() = runTest {
        val totals = diaryDao.dayTotals(DAY).first()

        assertEquals(0, totals.kcal)
        assertEquals(0, totals.entryCount)
    }

    /** Totals are scoped to one day: yesterday's calories are not today's. */
    @Test
    fun dayTotalsOnlyCountTheRequestedDay() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "e1", dayKey = DAY, kcal = 200))
        diaryDao.upsert(entry(id = "e2", dayKey = OTHER_DAY, kcal = 999))

        assertEquals(200, diaryDao.dayTotals(DAY).first().kcal)
        assertEquals(999, diaryDao.dayTotals(OTHER_DAY).first().kcal)
    }

    // -- Lexicographic week range (DB-2 / DB-5) ---------------------------------------------

    /**
     * The week range returns exactly the seven days asked for and leaks neither neighbouring week.
     *
     * Three weeks are seeded: the week before, the target week, the week after. The target week
     * **crosses a month boundary** (30 Sep -> 4 Oct on the previous side), which is the case a
     * non-padded or differently-ordered day key gets wrong: `"2026-09-28"` sorts *after*
     * `"2026-10-05"` the moment the month is not zero-padded, which silently drops the first days
     * of October from the report.
     *
     * The half-open upper bound is what keeps the following Monday out. An inclusive
     * `dayKey <= '2026-10-12'` would return 8 days and quietly inflate the weekly average — the
     * kind of off-by-one that is invisible on a week where nobody ate anything.
     */
    @Test
    fun theWeekRangeReturnsExactlySevenDaysAndNoNeighbouringWeek() = runTest {
        seedCatalog()

        val previousWeek = listOf("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01",
            "2026-10-02", "2026-10-03", "2026-10-04")
        val targetWeek = listOf("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08",
            "2026-10-09", "2026-10-10", "2026-10-11")
        val nextWeek = listOf("2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15",
            "2026-10-16", "2026-10-17", "2026-10-18")

        (previousWeek + targetWeek + nextWeek).forEachIndexed { index, dayKey ->
            diaryDao.upsert(entry(id = "entry-$index", dayKey = dayKey, kcal = 100))
        }

        val inWeek = diaryDao.entriesForWeek(targetWeek.first(), nextWeek.first()).first()

        assertEquals(
            "week range must select exactly the requested 7 days",
            targetWeek,
            inWeek.map { it.dayKey }
        )
        assertEquals(7, inWeek.size)
        // The boundary entries exist precisely so the assertions above are not vacuous.
        assertEquals(21, (previousWeek + targetWeek + nextWeek).size)
    }

    /**
     * A single-day week — a `LocalDate` range that happens to start and end inside one week — is
     * the degenerate case of the same query, and it must not over-select.
     */
    @Test
    fun aWeekRangeOfOneDayReturnsOnlyThatDay() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "before", dayKey = "2026-10-04", kcal = 100))
        diaryDao.upsert(entry(id = "target", dayKey = "2026-10-05", kcal = 100))
        diaryDao.upsert(entry(id = "after", dayKey = "2026-10-06", kcal = 100))

        val inWeek = diaryDao.entriesForWeek("2026-10-05", "2026-10-06").first()

        assertEquals(listOf("target"), inWeek.map { it.id })
    }

    /** A week the user has not logged anything in is empty, not an error. */
    @Test
    fun anEmptyWeekReturnsAnEmptyList() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "elsewhere", dayKey = "2026-10-12", kcal = 100))

        assertEquals(emptyList<DiaryEntryEntity>(), diaryDao.entriesForWeek("2026-10-05", "2026-10-12").first())
    }

    // -- Reads, upsert, delete ---------------------------------------------------------------

    @Test
    fun entriesForDayAreReturnedOldestFirst() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "late", dayKey = DAY, kcal = 10, loggedAt = 3_000L))
        diaryDao.upsert(entry(id = "early", dayKey = DAY, kcal = 20, loggedAt = 1_000L))
        diaryDao.upsert(entry(id = "middle", dayKey = DAY, kcal = 30, loggedAt = 2_000L))

        assertEquals(
            listOf("early", "middle", "late"),
            diaryDao.entriesForDay(DAY).first().map { it.id }
        )
    }

    /**
     * Upsert is keyed on the client-generated id, so a retried offline write updates one row
     * instead of appending a duplicate. Duplicated entries would double-count the day's `SUM`,
     * which is the number the whole "Inicio" screen is built on.
     */
    @Test
    fun upsertingTheSameIdReplacesTheEntryInsteadOfDuplicatingIt() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "same-id", dayKey = DAY, kcal = 200))
        diaryDao.upsert(entry(id = "same-id", dayKey = DAY, kcal = 500))

        val entries = diaryDao.entriesForDay(DAY).first()

        assertEquals(1, entries.size)
        assertEquals(500, entries.single().kcal)
        assertEquals(500, diaryDao.dayTotals(DAY).first().kcal)
    }

    @Test
    fun deleteByIdRemovesOnlyThatEntry() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "keep", dayKey = DAY, kcal = 100))
        diaryDao.upsert(entry(id = "drop", dayKey = DAY, kcal = 400))

        val deleted = diaryDao.deleteById("drop")

        assertEquals(1, deleted)
        assertEquals(listOf("keep"), diaryDao.entriesForDay(DAY).first().map { it.id })
        assertEquals(100, diaryDao.dayTotals(DAY).first().kcal)
    }

    /**
     * Deleting an id that is already gone is a success with zero rows affected, because
     * `DiaryRepository.deleteEntry` specifies a missing id as a no-op — the row may have been
     * removed by an earlier drain of the same offline queue.
     */
    @Test
    fun deletingAnUnknownIdIsANoOpSuccess() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "present", dayKey = DAY, kcal = 100))

        assertEquals(0, diaryDao.deleteById("never-existed"))
        assertEquals(1, diaryDao.entriesForDay(DAY).first().size)
    }

    @Test
    fun recentEntriesAreNewestFirstAndRespectTheLimit() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "oldest", dayKey = DAY, kcal = 10, loggedAt = 1_000L))
        diaryDao.upsert(entry(id = "newest", dayKey = DAY, kcal = 30, loggedAt = 3_000L))
        diaryDao.upsert(entry(id = "middle", dayKey = DAY, kcal = 20, loggedAt = 2_000L))

        val recent = diaryDao.recentEntries(limit = 2).first()

        assertEquals(listOf("newest", "middle"), recent.map { it.id })
    }

    @Test
    fun recentEntriesSpanDays() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "old-day", dayKey = "2026-09-30", kcal = 10, loggedAt = 1_000L))
        diaryDao.upsert(entry(id = "new-day", dayKey = "2026-10-05", kcal = 20, loggedAt = 9_000L))

        assertEquals(
            listOf("new-day", "old-day"),
            diaryDao.recentEntries(limit = 5).first().map { it.id }
        )
    }

    /**
     * Two entries logged in the same millisecond still produce a stable order, so a `LIMIT` cannot
     * return a different row on each call. Without a tiebreaker the scan history strip flickers.
     */
    @Test
    fun entriesSharingATimestampStillHaveADeterministicOrder() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "aaa", dayKey = DAY, kcal = 10, loggedAt = 5_000L))
        diaryDao.upsert(entry(id = "bbb", dayKey = DAY, kcal = 20, loggedAt = 5_000L))

        val runs = List(5) { diaryDao.recentEntries(limit = 1).first().single().id }

        assertEquals(1, runs.toSet().size)
    }

    // -- Enum persistence -------------------------------------------------------------------

    /**
     * The stored value is the constant name, readable in the raw column. If the converter ever
     * regressed to `label`, every row would still read back as a valid enum and only this assertion
     * would notice.
     */
    @Test
    fun theMealSlotColumnHoldsTheConstantName() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "e1", dayKey = DAY, kcal = 100, mealSlot = MealSlot.ALMUERZO))

        val stored = rawString("SELECT mealSlot FROM diary_entries WHERE id = 'e1'")

        assertEquals("ALMUERZO", stored)
        assertEquals(MealSlot.ALMUERZO, diaryDao.entriesForDay(DAY).first().single().mealSlot)
    }

    @Test
    fun everyMealSlotSurvivesARoundTripThroughTheDatabase() = runTest {
        seedCatalog()
        MealSlot.entries.forEachIndexed { index, slot ->
            diaryDao.upsert(entry(id = "slot-$index", dayKey = DAY, kcal = 10, mealSlot = slot))
        }

        assertEquals(
            MealSlot.entries.toSet(),
            diaryDao.entriesForDay(DAY).first().map { it.mealSlot }.toSet()
        )
    }

    // -- Foreign key policy (the RESTRICT decision) ------------------------------------------

    /**
     * Deleting a catalog row that history depends on **fails loudly**.
     *
     * This is the behaviour that keeps a routine cache trim from silently deleting the user's
     * diary. With `CASCADE` this delete would succeed and take three entries with it; nothing
     * would be reported, and the totals would just be smaller. Asserting the failure is what makes
     * the policy a decision rather than a hope.
     *
     * `CatalogDao` has no delete method on purpose — the eviction path is not written yet — so the
     * constraint is exercised at the SQL layer, which is where the policy actually lives.
     */
    @Test
    fun deletingAReferencedCatalogRowIsRejected() = runTest {
        seedCatalog()
        diaryDao.upsert(entry(id = "e1", dayKey = DAY, kcal = 200))
        diaryDao.upsert(entry(id = "e2", dayKey = DAY, kcal = 200))
        diaryDao.upsert(entry(id = "e3", dayKey = DAY, kcal = 200))

        assertThrows(SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase
                .execSQL("DELETE FROM food_products WHERE barcode = '$productBarcode'")
        }

        assertEquals(3, diaryDao.entriesForDay(DAY).first().size)
        assertEquals(600, diaryDao.dayTotals(DAY).first().kcal)
    }

    /** The policy is asymmetric on purpose: an unreferenced cache row is still evictable. */
    @Test
    fun deletingAnUnreferencedCatalogRowIsAllowed() = runTest {
        seedCatalog()

        db.openHelper.writableDatabase
            .execSQL("DELETE FROM food_products WHERE barcode = '$productBarcode'")

        assertNull(db.catalogDao().byBarcode(productBarcode).first())
    }

    /**
     * The FK is enforced on insert too: an entry cannot reference a product that is not cached.
     *
     * Written as try/catch rather than `assertThrows` because `upsert` is `suspend` and
     * `assertThrows`' lambda is not a coroutine body; bridging that with a nested `runBlocking`
     * inside `runTest` would block the test thread on the very dispatcher driving the test.
     */
    @Test
    fun anEntryCannotReferenceAMissingCatalogRow() = runTest {
        seedCatalog()
        db.openHelper.writableDatabase.execSQL("DELETE FROM food_products WHERE barcode = '$productBarcode'")

        val thrown = try {
            diaryDao.upsert(entry(id = "orphan", dayKey = DAY, kcal = 100))
            null
        } catch (error: SQLiteConstraintException) {
            error
        }

        assertNotNull("the foreign key must reject an entry with no catalog row", thrown)
        assertEquals(emptyList<DiaryEntryEntity>(), diaryDao.entriesForDay(DAY).first())
    }

    // -- Fixtures ----------------------------------------------------------------------------

    private suspend fun seedCatalog() {
        db.catalogDao().upsert(
            FoodProductEntity(
                barcode = productBarcode,
                name = "Arepa de choclo con queso",
                brand = "Local",
                servingGrams = 90.0,
                kcalPer100g = 294,
                carbsPer100g = 31.0,
                proteinPer100g = 10.0,
                fatPer100g = 12.0,
                sugarsPer100g = 2.0,
                fiberPer100g = 1.5,
                sodiumPer100g = 320.0,
                categories = listOf("Beverages,Alcoholic beverages", "", "Snacks")
            )
        )
    }

    private fun entry(
        id: String,
        dayKey: String,
        kcal: Int,
        carbsG: Double = 0.0,
        proteinG: Double = 0.0,
        fatG: Double = 0.0,
        sugarsG: Double = 0.0,
        fiberG: Double = 0.0,
        sodiumG: Double = 0.0,
        mealSlot: MealSlot = MealSlot.ALMUERZO,
        loggedAt: Long = 1_700_000_000_000L
    ) = DiaryEntryEntity(
        id = id,
        productBarcode = productBarcode,
        servings = 1,
        mealSlot = mealSlot,
        loggedAtEpochMillis = loggedAt,
        dayKey = dayKey,
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        sugarsG = sugarsG,
        fiberG = fiberG,
        sodiumG = sodiumG
    )

    private fun rawString(sql: String): String? = db.openHelper.writableDatabase
        .query(sql)
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        /** A Monday, because ISO weeks start on Monday. */
        const val DAY = "2026-10-05"
        const val EMPTY_DAY = "2026-10-06"
        const val OTHER_DAY = "2026-10-07"

        /** Guards only SQLite's accumulation order; the fixtures are exactly representable. */
        const val TOLERANCE = 1e-9
    }
}