package com.racion.diariomercado.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.racion.diariomercado.data.local.DayNutritionTotalsByDay
import com.racion.diariomercado.data.local.DiaryEntryWithProduct
import com.racion.diariomercado.data.local.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The local diary: entries, and the day aggregate the "Inicio" screen renders.
 *
 * ## Every read is a [Flow]
 * Per `DiaryRepository`: the diary is the one genuinely local-first part of the app, so a screen
 * must render from cache on the first frame and re-render when the local write lands, without
 * anything waiting on a network. A `suspend` read that returns a value would force the collector
 * to re-query on every lifecycle event instead of subscribing once to a stream Room keeps hot.
 *
 * ## Ordering is total, not incidental
 * Every `ORDER BY` here ends with a tiebreaker on the primary key. Two entries logged in the same
 * millisecond are common (the confirm screen writes fast), and a `LIMIT` query without a
 * deterministic tiebreaker can return a different row on each call — so "the 5 most recent scans"
 * would flicker, and the strip on the scan screen would show duplicates instead of distinct
 * products.
 */
@Dao
interface DiaryDao {

    /**
     * Every entry on one calendar day, oldest first.
     *
     * Equality, not a range: a day is a single [DiaryEntryEntity.dayKey] value, and the
     * zero-padded `yyyy-MM-dd` format is what makes that equality correct (see the entity's note on
     * why the format is load-bearing).
     */
    @Query(
        """
        SELECT * FROM diary_entries
        WHERE dayKey = :dayKey
        ORDER BY loggedAtEpochMillis ASC, id ASC
        """
    )
    fun entriesForDay(dayKey: String): Flow<List<DiaryEntryEntity>>

    /**
     * Every entry in an ISO week, as a **lexicographic range** on [DiaryEntryEntity.dayKey].
     *
     * ## Why a range and not seven equality queries
     * `dayKey` is a zero-padded `yyyy-MM-dd` string, and for equal-length strings over the same
     * alphabet string order is chronological order. So the whole week is one indexed range scan:
     * `WHERE dayKey >= '2026-10-05' AND dayKey < '2026-10-12'` is Monday 00:00 through Sunday
     * 23:59, with no date arithmetic in SQL and no seven-way `OR`. The index on `dayKey` (DB-5)
     * exists precisely to serve this.
     *
     * The variant a naive implementation gets wrong is comparing epoch millis and assuming the
     * numeric range matches the calendar week: DST makes the last Sunday of a week 25 hours long,
     * so a `>= startMillis AND < endMillis` window that assumes 7 x 24h silently drops or duplicates
     * an hour of diary entries once a month. Day keys have no such failure mode, because no day
     * *is* 25 hours — it is a label, not an interval.
     *
     * ## Why the upper bound is exclusive
     * `[from, to)` rather than `BETWEEN`. An inclusive end has to be the *last day of the week*,
     * so the caller must add 6 days and hope; an exclusive end is the *start of the next week*,
     * which is what a caller already has when it walks week to week, and it cannot over-select a
     * day at all. It also sidesteps the `BETWEEN` off-by-one at a month boundary
     * (`'2026-10-31' BETWEEN '2026-10-01' AND '2026-10-31'` is fine, `'2026-11-01'` is not).
     *
     * [toDayKeyExclusive] must be the day key of the Monday **after** the requested week. The DAO
     * does not compute it: day-key formatting is a mapping-layer concern, and duplicating the
     * padding rule in two places is how the range query starts leaking neighbouring weeks.
     */
    @Query(
        """
        SELECT * FROM diary_entries
        WHERE dayKey >= :fromDayKey AND dayKey < :toDayKeyExclusive
        ORDER BY dayKey ASC, loggedAtEpochMillis ASC, id ASC
        """
    )
    fun entriesForWeek(fromDayKey: String, toDayKeyExclusive: String): Flow<List<DiaryEntryEntity>>

    /**
     * Most recently logged entries across all days, newest first.
     *
     * Backs `DiaryRepository.recentScans`, the history strip on the scan screen. The `LIMIT` is a
     * parameter because the contract's default is `5` and a caller rendering a longer strip should
     * not have to bypass the DAO.
     */
    @Query(
        """
        SELECT * FROM diary_entries
        ORDER BY loggedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """
    )
    fun recentEntries(limit: Int): Flow<List<DiaryEntryEntity>>

    /**
     * The day's totals, computed by SQL `SUM` over the seven flattened nutrition columns.
     *
     * ## Why an aggregate and not a stored document
     * A `days/{yyyy-MM-dd}` row holding precomputed totals is what the *remote* design does
     * (`FirestoreDiaryRepository`), because a Firestore document costs a read and a write per
     * change and the write is contended by every concurrent entry. SQLite has neither problem: a
     * local aggregate over an indexed range of a few dozen rows is microseconds, it can never drift
     * from the entries it was derived from, and it needs no maintenance path when an entry is
     * edited, retried, or restored from a backup. The remote counter exists to serve remote reads;
     * copying it locally would import its write-contention problem for nothing.
     *
     * ## Why `COALESCE(..., 0)` on every `SUM`
     * `SUM` over zero rows returns `NULL`, and a `NULL` in a non-null Kotlin field is either a
     * `NullPointerException` at the mapping boundary or a silent zero depending on the version.
     * Worse than either: a nullable total makes "the user ate nothing" (`0`) and "the query is
     * broken" (`null`) the same value to a caller that has to tell them apart. `COALESCE` pins the
     * empty day to a real zero.
     *
     * `0.0`, not `0`, on the `REAL` columns so the literal's type matches the column's and the
     * result stays a `Double` rather than depending on SQLite's affinity rules.
     *
     * `COUNT(*)` is the one aggregate that needs no `COALESCE`: over an empty set it already
     * returns `0`, never `null`. It rides along free because the rows are already being walked,
     * and it gives the repository the local reconciliation counter that `DailySummary` documents
     * as server-maintained.
     *
     * ## Why an aggregate without `GROUP BY` always emits one row
     * A bare aggregate over an empty set still produces exactly one row of zeroes, so this flow
     * emits a value for an empty day instead of completing empty. `observeDay` promises the
     * collector an "empty day" emission rather than an exception, and the query is where that
     * promise is kept.
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(kcal), 0) AS kcal,
            COALESCE(SUM(carbsG), 0.0) AS carbsG,
            COALESCE(SUM(proteinG), 0.0) AS proteinG,
            COALESCE(SUM(fatG), 0.0) AS fatG,
            COALESCE(SUM(sugarsG), 0.0) AS sugarsG,
            COALESCE(SUM(fiberG), 0.0) AS fiberG,
            COALESCE(SUM(sodiumG), 0.0) AS sodiumG,
            COUNT(*) AS entryCount
        FROM diary_entries
        WHERE dayKey = :dayKey
        """
    )
    fun dayTotals(dayKey: String): Flow<DayNutritionTotals>

    /**
     * Insert or replace by [DiaryEntryEntity.id].
     *
     * `@Upsert` rather than `INSERT OR REPLACE` because the id is client-generated: a retried
     * offline write of the same entry must update one row, not append a duplicate. Note that
     * `REPLACE` semantics would also fire the FK's delete branch, and with `onDelete = RESTRICT`
     * that is a second reason to avoid the manual conflict strategy.
     */
    @Upsert
    suspend fun upsert(entry: DiaryEntryEntity)

    /**
     * Deletes one entry and returns how many rows went.
     *
     * A return value of `0` is the success case, not an error: `DiaryRepository.deleteEntry`
     * specifies a missing id as a no-op success, because the row may already be gone from a
     * retried offline queue drain.
     */
    @Query("DELETE FROM diary_entries WHERE id = :entryId")
    suspend fun deleteById(entryId: String): Int

    // ─── Joined reads ───────────────────────────────────────────────
    //
    // These sit ALONGSIDE the entity-only queries above, not instead of them. The entity-only
    // reads are what the SUM aggregate and the existing DiaryDaoTest assertions are written
    // against, and they remain the cheapest way to ask "what are the totals for this day".
    // These three add the catalog columns the DOMAIN DiaryEntry needs, which the entity
    // deliberately does not store.
    //
    // Every product column is aliased. `diary_entries` and `food_products` both carry a
    // barcode-flavoured column and Room binds projections by name, so an unaliased `p.*`
    // collides.
    //
    // INNER JOIN, not LEFT JOIN: `productBarcode` is a RESTRICT foreign key, so a row without
    // its product is not representable. There is no orphaned entry to degrade gracefully for,
    // and a LEFT JOIN would only invite someone to write the null branch that can never fire.
    //
    // The SELECT list is spelled out in all three rather than shared through a constant: Room
    // needs a compile-time literal, and a shared fragment would be invisible at the call site
    // where someone edits a column.

    /** Joined rows for one day, oldest first. Same ordering contract as [entriesForDay]. */
    @Query(
        """
        SELECT e.id AS entryId,
               e.servings, e.mealSlot, e.loggedAtEpochMillis, e.dayKey,
               e.kcal, e.carbsG, e.proteinG, e.fatG,
               e.sugarsG, e.fiberG, e.sodiumG,
               p.barcode AS productBarcode, p.name AS productName,
               p.brand AS productBrand, p.quantityLabel AS productQuantityLabel,
               p.servingGrams AS productServingGrams,
               p.kcalPer100g AS productKcalPer100g,
               p.carbsPer100g AS productCarbsPer100g,
               p.proteinPer100g AS productProteinPer100g,
               p.fatPer100g AS productFatPer100g,
               p.sugarsPer100g AS productSugarsPer100g,
               p.fiberPer100g AS productFiberPer100g,
               p.sodiumPer100g AS productSodiumPer100g,
               p.imageUrl AS productImageUrl,
               p.nutriscoreGrade AS productNutriscoreGrade,
               p.categories AS productCategories,
               p.ingredientsText AS productIngredientsText,
               p.emoji AS productEmoji
        FROM diary_entries e
        INNER JOIN food_products p ON p.barcode = e.productBarcode
        WHERE e.dayKey = :dayKey
        ORDER BY e.loggedAtEpochMillis ASC, e.id ASC
        """
    )
    fun entriesWithProductForDay(dayKey: String): Flow<List<DiaryEntryWithProduct>>

    /**
     * Joined rows for a week, over the same **semi-open** lexical range as [entriesForWeek].
     *
     * The bound semantics are inherited on purpose: `[fromDayKey, toDayKeyExclusive)` is the
     * Monday-after rule. Two different conventions for "a week" inside one DAO is how a query
     * starts quietly leaking the neighbouring week.
     */
    @Query(
        """
        SELECT e.id AS entryId,
               e.servings, e.mealSlot, e.loggedAtEpochMillis, e.dayKey,
               e.kcal, e.carbsG, e.proteinG, e.fatG,
               e.sugarsG, e.fiberG, e.sodiumG,
               p.barcode AS productBarcode, p.name AS productName,
               p.brand AS productBrand, p.quantityLabel AS productQuantityLabel,
               p.servingGrams AS productServingGrams,
               p.kcalPer100g AS productKcalPer100g,
               p.carbsPer100g AS productCarbsPer100g,
               p.proteinPer100g AS productProteinPer100g,
               p.fatPer100g AS productFatPer100g,
               p.sugarsPer100g AS productSugarsPer100g,
               p.fiberPer100g AS productFiberPer100g,
               p.sodiumPer100g AS productSodiumPer100g,
               p.imageUrl AS productImageUrl,
               p.nutriscoreGrade AS productNutriscoreGrade,
               p.categories AS productCategories,
               p.ingredientsText AS productIngredientsText,
               p.emoji AS productEmoji
        FROM diary_entries e
        INNER JOIN food_products p ON p.barcode = e.productBarcode
        WHERE e.dayKey >= :fromDayKey AND e.dayKey < :toDayKeyExclusive
        ORDER BY e.dayKey ASC, e.loggedAtEpochMillis ASC, e.id ASC
        """
    )
    fun entriesWithProductForWeek(
        fromDayKey: String,
        toDayKeyExclusive: String
    ): Flow<List<DiaryEntryWithProduct>>

    /** Joined rows for the scan-history strip, newest first. Backs `DiaryRepository.recentScans`. */
    @Query(
        """
        SELECT e.id AS entryId,
               e.servings, e.mealSlot, e.loggedAtEpochMillis, e.dayKey,
               e.kcal, e.carbsG, e.proteinG, e.fatG,
               e.sugarsG, e.fiberG, e.sodiumG,
               p.barcode AS productBarcode, p.name AS productName,
               p.brand AS productBrand, p.quantityLabel AS productQuantityLabel,
               p.servingGrams AS productServingGrams,
               p.kcalPer100g AS productKcalPer100g,
               p.carbsPer100g AS productCarbsPer100g,
               p.proteinPer100g AS productProteinPer100g,
               p.fatPer100g AS productFatPer100g,
               p.sugarsPer100g AS productSugarsPer100g,
               p.fiberPer100g AS productFiberPer100g,
               p.sodiumPer100g AS productSodiumPer100g,
               p.imageUrl AS productImageUrl,
               p.nutriscoreGrade AS productNutriscoreGrade,
               p.categories AS productCategories,
               p.ingredientsText AS productIngredientsText,
               p.emoji AS productEmoji
        FROM diary_entries e
        INNER JOIN food_products p ON p.barcode = e.productBarcode
        ORDER BY e.loggedAtEpochMillis DESC, e.id DESC
        LIMIT :limit
        """
    )
    fun recentEntriesWithProduct(limit: Int): Flow<List<DiaryEntryWithProduct>>

    /**
     * One joined row by id, or `null` if no such entry exists. The **same projection** as the three
     * queries above, deliberately not a trimmed one.
     *
     * ## Why this read exists: the delete outbox needs the row it is about to erase
     * `LocalDiaryRepository.deleteEntry` must enqueue a replayable DELETE, and design D2 says that
     * row carries the deleted entry's nutrition (for the compensating `decrement` on the remote day
     * aggregate) and its product identity. `deleteEntry` receives only an id, so the values have to
     * be read here, inside the same transaction that removes the row — after the `DELETE` they are
     * gone, which is the entire argument for an outbox table over a dirty-flag column.
     *
     * Full projection rather than the `kcal` and `p.name` that the outbox happens to need today: a
     * trimmed projection is a second definition of "what an entry is", and the next column anyone
     * adds to `DiaryEntry` would land here as a silent omission instead of a compile error. The
     * cost of the wide `SELECT` is a primary-key lookup; the cost of the narrow one is a bug that
     * only shows up as a wrong number in someone's calorie total weeks later.
     *
     * INNER JOIN for the same reason as the others: `productBarcode` is RESTRICT, so a row without
     * its product is not representable and there is no orphan to degrade for.
     *
     * A `suspend` single read, not a `Flow`: this is one lookup on a write path, not a screen
     * binding. A `Flow` here would allocate an invalidation tracker observation to read a row that
     * is about to be deleted.
     */
    @Query(
        """
        SELECT e.id AS entryId,
               e.servings, e.mealSlot, e.loggedAtEpochMillis, e.dayKey,
               e.kcal, e.carbsG, e.proteinG, e.fatG,
               e.sugarsG, e.fiberG, e.sodiumG,
               p.barcode AS productBarcode, p.name AS productName,
               p.brand AS productBrand, p.quantityLabel AS productQuantityLabel,
               p.servingGrams AS productServingGrams,
               p.kcalPer100g AS productKcalPer100g,
               p.carbsPer100g AS productCarbsPer100g,
               p.proteinPer100g AS productProteinPer100g,
               p.fatPer100g AS productFatPer100g,
               p.sugarsPer100g AS productSugarsPer100g,
               p.fiberPer100g AS productFiberPer100g,
               p.sodiumPer100g AS productSodiumPer100g,
               p.imageUrl AS productImageUrl,
               p.nutriscoreGrade AS productNutriscoreGrade,
               p.categories AS productCategories,
               p.ingredientsText AS productIngredientsText,
               p.emoji AS productEmoji
        FROM diary_entries e
        INNER JOIN food_products p ON p.barcode = e.productBarcode
        WHERE e.id = :entryId
        """
    )
    suspend fun withProductById(entryId: String): DiaryEntryWithProduct?

    /**
     * Per-day totals across a week range, one row per day **that has at least one entry**.
     *
     * `GROUP BY dayKey` over the same indexed semi-open range, so a quiet week costs one scan
     * instead of seven equality lookups. The days with no rows are absent from the result and
     * the mapping layer fills them with explicit zeroes — `WeeklyReport.days` must always carry
     * seven bars, and a missing bar would silently change the chart's x-axis.
     *
     * `COALESCE` is defensive here rather than strictly required: `GROUP BY` only emits groups
     * that exist, so a group always has at least one row. It is kept so this query cannot become
     * a nullable-projection trap if it is ever reused over an outer join.
     */
    @Query(
        """
        SELECT dayKey,
               COALESCE(SUM(kcal), 0) AS kcal,
               COALESCE(SUM(carbsG), 0.0) AS carbsG,
               COALESCE(SUM(proteinG), 0.0) AS proteinG,
               COALESCE(SUM(fatG), 0.0) AS fatG,
               COALESCE(SUM(sugarsG), 0.0) AS sugarsG,
               COALESCE(SUM(fiberG), 0.0) AS fiberG,
               COALESCE(SUM(sodiumG), 0.0) AS sodiumG,
               COUNT(*) AS entryCount
        FROM diary_entries
        WHERE dayKey >= :fromDayKey AND dayKey < :toDayKeyExclusive
        GROUP BY dayKey
        ORDER BY dayKey ASC
        """
    )
    fun dayTotalsByDay(
        fromDayKey: String,
        toDayKeyExclusive: String
    ): Flow<List<DayNutritionTotalsByDay>>
}

/**
 * One row of [DiaryDao.dayTotals]: the seven `SUM` columns plus the entry count.
 *
 * ## Why this is not the domain `Nutrition`
 * `DailySummary`'s KDoc records the trap that decides it: all seven fields are stored because
 * `consumed` is a `Nutrition`, and a field missing from storage deserialises to `0.0` —
 * indistinguishable from "the user consumed none of it". Reusing `Nutrition` as a Room
 * projection would put that hazard on the read path of the one query the whole "Inicio" screen
 * depends on: a future column added to `Nutrition`, or one renamed in SQL, would silently read as
 * zero grams of that macro instead of failing.
 *
 * A dedicated projection type makes every column explicit and positional, keeps the aggregate
 * shape separate from the domain shape, and gives [entryCount] somewhere to live — it is a
 * read-only reconciliation counter, and `DailySummary` deliberately keeps it out of the domain
 * object because a value that can drift should not be loaded into a model the UI binds to.
 */
data class DayNutritionTotals(
    val kcal: Int,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarsG: Double,
    val fiberG: Double,
    val sodiumG: Double,
    /** Entries that contributed to the sums above. */
    val entryCount: Int
)