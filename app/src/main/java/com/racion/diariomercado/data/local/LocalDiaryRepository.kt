package com.racion.diariomercado.data.local

import androidx.room.withTransaction
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.local.dao.CatalogDao
import com.racion.diariomercado.data.local.dao.DayNutritionTotals
import com.racion.diariomercado.data.local.dao.DiaryDao
import com.racion.diariomercado.data.local.dao.GoalsDao
import com.racion.diariomercado.data.local.dao.SyncOutboxDao
import com.racion.diariomercado.data.local.entity.DiaryEntryEntity
import com.racion.diariomercado.data.local.entity.FoodProductEntity
import com.racion.diariomercado.data.local.entity.SyncOutOp
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.domain.model.DailySummary
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.DayTotal
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.MacroSplit
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.model.WeeklyReport
import com.racion.diariomercado.domain.repository.DiaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The local [DiaryRepository]: ROOM is the source of truth and every read is served from it.
 *
 * ## Why this repository exists at all
 * `FirestoreDiaryRepository` is the remote projection (FF-6). It cannot be the app's
 * `DiaryRepository`, for two independent reasons: it throws `NotImplementedError` today, and even
 * once implemented it is the wrong shape for this app. The roadmap fixes the ordering — "toda
 * escritura va primero a la base local; Firestore es la proyección de sync/backup, nunca el
 * primer destino" — and an app used in a market cannot make its home screen wait on a network
 * round trip to render yesterday's diary.
 *
 * ## Writes never touch the network, by design
 * [addEntry] and [deleteEntry] write to ROOM and return. There is no `runCatching` around a
 * remote call here, because there is no remote call: a write that cannot reach the network must
 * still be accepted locally, per the `DiaryRepository` contract. What changed in DB-7 is the
 * second half of that promise — each write now also records what it owes the server in
 * `sync_outbox`, in the same transaction, so "accepted locally" stops meaning "accepted locally
 * and then forgotten". The upload itself belongs to `DiarySyncManager` and to a transport this
 * repository has no reference to.
 *
 * ## [now] exists so the queue order is a test fact and not a coincidence
 * `System.currentTimeMillis()` would make `sync_outbox.enqueuedAtEpochMillis` untestable: every
 * test that enqueues two rows "at the same time" is actually asserting on a tie it cannot control,
 * and the drain's `ORDER BY` tiebreaker test would pass by luck. Injecting the clock makes
 * oldest-first something a test states rather than something it hopes for.
 *
 * ## The two observable guarantees
 * `observeDay` and `observeWeek` never throw at the collector: a read failure becomes an empty
 * day or an empty week. Note the honest limit of that: `.catch` emits the fallback and then the
 * flow **completes**, so a caller that needs a permanently hot stream should re-collect. In
 * practice ROOM's `InvalidationTracker` does not fail a query the way a network read does — this
 * is the belt-and-braces path, not the expected one.
 *
 * ## [userId] is a seam, not the real uid yet
 * `GoalsDao` is keyed by `userId` so two accounts can coexist on one phone. `AuthRepository` does
 * not expose the uid yet ("the uid lives in the repository, not here"), so [AppContainer][1]
 * passes a device-local sentinel. **This is a substitution point, not a decision**: when auth
 * exposes the uid, the only change is the value handed to this constructor. Defaults are used
 * for a `null` goals row, which is the real "user never opened Metas" state — defaulting inside
 * the DAO instead would write a row on first read and make "the user chose this" and "we
 * invented this" the same state.
 *
 * [1]: `di/AppContainer.kt`
 */
class LocalDiaryRepository(
    private val diaryDao: DiaryDao,
    private val catalogDao: CatalogDao,
    private val goalsDao: GoalsDao,
    private val syncOutboxDao: SyncOutboxDao,
    private val database: RacionDatabase,
    private val userId: String,
    /**
     * Clock for `sync_outbox.enqueuedAtEpochMillis`. Injected rather than called statically so the
     * drain's oldest-first ordering is testable; production passes `System::currentTimeMillis`.
     */
    private val now: () -> Long = System::currentTimeMillis
) : DiaryRepository {

    override fun observeDay(date: LocalDate): Flow<DailySummary> {
        val dayKey = date.toDayKey()
        return combine(
            diaryDao.entriesWithProductForDay(dayKey),
            diaryDao.dayTotals(dayKey),
            goalsDao.observeGoals(userId)
        ) { entries, totals, goals ->
            DailySummary(
                dateLabel = date.dayLabel(),
                // From the SQL SUM, not a Kotlin fold over `entries`: the aggregate is the
                // contract (DB-4) and it cannot drift from the rows it came from.
                consumed = totals.toNutrition(),
                goalKcal = goals?.kcalPerDay ?: DEFAULT_GOAL_KCAL,
                entries = entries.map { it.toDomain() }
            )
        }.catch { emit(emptySummary(date)) }
    }

    override fun observeWeek(weekStart: LocalDate): Flow<WeeklyReport> {
        val from = weekStart.toDayKey()
        val to = weekStart.plusDays(7).toDayKey()
        return combine(
            diaryDao.dayTotalsByDay(from, to),
            goalsDao.observeGoals(userId)
        ) { totalsByDay, goals ->
            buildWeeklyReport(weekStart, totalsByDay.associateBy { it.dayKey }, goals?.kcalPerDay)
        }.catch { emit(emptyWeek(weekStart)) }
    }

    /**
     * Persists one entry locally.
     *
     * ## The catalog row is written in the same transaction, and that is not optional
     * `diary_entries.productBarcode` is a RESTRICT foreign key. Inserting the entry without its
     * product fails with `SQLiteConstraintException`, so a repository that wrote only the entry
     * would work perfectly in a test that pre-seeded the catalog and fail on the first scan of a
     * product this phone had never looked up — which is the common case, not the edge case.
     *
     * Both writes go through [withTransaction] so a crash between them cannot leave an entry
     * pointing at a product that was never cached.
     *
     * ## The outbox enqueue is the third write in that same transaction, and it is load-bearing
     * An entry that exists locally with nothing queued for it is an entry the server will never
     * learn about, and it looks perfectly healthy: the diary renders, the totals are right, nothing
     * anywhere says the change is unsynced. Because the enqueue is inside [withTransaction], that
     * state is unreachable — the entry row and its sync row commit together or not at all.
     *
     * Enqueueing *outside* the transaction is the tempting version and it has two distinct failure
     * modes, which is why it is worth naming: a crash before the enqueue loses the sync record
     * silently, and a crash after it leaves a row describing an entry that was never written, which
     * `DiarySyncManager` would then faithfully upload — creating a server document for a product the
     * user never logged.
     *
     * `UPSERT` and not a delete-then-upsert: this is the "user added or edited an entry" path, and
     * [deleteEntry] is the one that owes a DELETE.
     */
    override suspend fun addEntry(entry: DiaryEntry): AppResult<Unit> = runCatching {
        val dayKey = LocalDate.ofEpochDay(
            entry.loggedAtEpochMillis / MILLIS_PER_DAY_UTC
        ).toDayKey()
        database.withTransaction {
            catalogDao.upsert(entry.product.toEntity())
            diaryDao.upsert(entry.toEntity(dayKey))
            syncOutboxDao.enqueue(entry.toOutboxRow(SyncOutOp.UPSERT, dayKey, now()))
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Failure(AppError.Unknown(it)) }
    )

    /**
     * Deletes one entry. A missing id is a **success**, not `NotFound`.
     *
     * `deleteById` returns the row count and the DELETE enqueue ignores it on purpose: the
     * observable state the user cares about is already correct if the row is gone, and a retried
     * offline drain can legitimately arrive after the row was removed. Reporting `NotFound` would
     * make a correct idempotent retry look like a failure to the caller.
     *
     * ## Why the row is read *before* it is deleted
     * The queued DELETE has to carry the entry's nutrition and product name, because confirming it
     * server-side means decrementing a precomputed day aggregate by exactly those numbers (design
     * D2). Once the `DELETE` runs they are gone — which is the whole reason the outbox is a table
     * with the payload on it rather than a flag on the row. Read, delete and enqueue all happen in
     * one [withTransaction], so there is no window in which a crash leaves a payload with nothing
     * to apply it to, or a deletion with nothing recording it.
     *
     * ## A `null` read enqueues nothing, and that is the correct outcome
     * `withProductById` returns `null` when the id was already gone. Nothing was deleted locally,
     * so nothing is owed to the server, and enqueueing a DELETE built from invented values would
     * subtract a phantom entry from the remote day total. The idempotent-retry path — the same call
     * twice — therefore stays a clean no-op on both sides.
     */
    override suspend fun deleteEntry(entryId: String): AppResult<Unit> = runCatching {
        database.withTransaction {
            val existing = diaryDao.withProductById(entryId)
            diaryDao.deleteById(entryId)
            if (existing != null) {
                syncOutboxDao.enqueue(existing.toOutboxRow(SyncOutOp.DELETE, now()))
            }
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Failure(AppError.Unknown(it)) }
    )

    override suspend fun recentScans(limit: Int): AppResult<List<DiaryEntry>> = runCatching {
        diaryDao.recentEntriesWithProduct(limit).first().map { it.toDomain() }
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.Unknown(it)) }
    )

    // ─── Weekly aggregation ─────────────────────────────────────────

    /**
     * Builds the seven bars, the average, the streak, the macro split and the highlight.
     *
     * Split out of the flow so it can be exercised directly by unit tests with hand-built
     * aggregates — the arithmetic is where this report actually goes wrong, and reaching it
     * through Room for every case would make the interesting inputs expensive to express.
     */
    private fun buildWeeklyReport(
        weekStart: LocalDate,
        totalsByDay: Map<String, DayNutritionTotalsByDay>,
        goalKcal: Int?
    ): WeeklyReport {
        val days = (0 until 7).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            val totals = totalsByDay[date.toDayKey()]
            DayTotal(
                label = date.dayOfWeek.toShortLabel(),
                kcal = totals?.kcal ?: 0
            )
        }

        // An all-zero week has no best day. Returning the first bar would highlight a Monday
        // that simply has no data, which reads as "your best day was Monday" rather than
        // "nothing to show".
        val bestKcal = days.maxOfOrNull { it.kcal } ?: 0
        // An all-zero week has no best day: every bar is 0, and highlighting one would read as
        // "your best day was Monday" rather than "nothing to show". Hence the `bestKcal > 0`
        // guard — `days` still has seven zero bars, which is what the chart needs.
        val highlighted = days.map { it.copy(isHighlighted = bestKcal > 0 && it.kcal == bestKcal) }

        val weekTotals = totalsByDay.values.fold(Nutrition()) { acc, day -> acc + day.toNutrition() }

        return WeeklyReport(
            weekRangeLabel = weekStart.weekRangeLabel(),
            // Over the SEVEN days, not over the days that happen to have entries. Averaging over
            // logged days only would report a 400 kcal week as "133 kcal/day" against a 1900
            // goal and render the progress bar as if the user were starving.
            averageKcalPerDay = (weekTotals.kcal / DAYS_IN_WEEK).roundToInt(),
            bestDayLabel = highlighted.firstOrNull { it.isHighlighted }?.label,
            activeStreakDays = activeStreakDays(weekStart, totalsByDay),
            macroSplit = macroSplit(weekTotals),
            days = highlighted,
            goalKcal = goalKcal ?: DEFAULT_GOAL_KCAL
        )
    }

    /**
     * Consecutive days with at least one entry, anchored on the **last day of the week that has
     * entries** and counted backwards.
     *
     * ## Why the anchor is the last logged day, not the end of the week
     * Counting back from Sunday reports a streak of 0 for a perfectly ordinary past week in which
     * the user logged Monday to Wednesday and stopped. That is the common case, not the edge case,
     * and a weekly card that reads "0 días activos" after three logged days is worse than showing
     * no streak at all. Anchoring on the last day with data gives the 3 the user earned, and for
     * the *current* week it naturally truncates at today, because today is the last day that can
     * have entries.
     *
     * A gap *before* that anchor still ends the streak — the run has to be contiguous, so
     * Mon/Tue, Thu/Fri is a streak of 2, not 4.
     *
     * Scoped to the requested week deliberately: a streak that began before this Monday would
     * start counting on a bar the screen does not draw, so the number would not correspond to
     * anything the user can see.
     */
    private fun activeStreakDays(
        weekStart: LocalDate,
        totalsByDay: Map<String, DayNutritionTotalsByDay>
    ): Int {
        fun hasEntries(offset: Long): Boolean =
            totalsByDay[weekStart.plusDays(offset).toDayKey()]?.entryCount?.let { it > 0 } ?: false

        val lastActiveOffset = (6L downTo 0L).firstOrNull { hasEntries(it) } ?: return 0
        var streak = 0
        for (offset in lastActiveOffset downTo 0L) {
            if (!hasEntries(offset)) break
            streak++
        }
        return streak
    }

    /**
     * Macro distribution as whole percentages of **energy**, not of grams.
     *
     * Grams would make the chart lie: 40 g of carbs and 40 g of fat are 160 and 360 kcal, so a
     * 50/50 split by mass renders as half the calories it actually is. 4/4/9 kcal per gram are the
     * standard Atwater factors. Percentages are truncated, not rounded, and are explicitly not
     * normalised to 100 — `MacroSplit` documents that, and a total of 98% is a rounding artefact
     * the UI should not be asked to explain.
     */
    private fun macroSplit(totals: Nutrition): MacroSplit {
        val carbsKcal = totals.carbsG * 4.0
        val proteinKcal = totals.proteinG * 4.0
        val fatKcal = totals.fatG * 9.0
        val totalKcal = carbsKcal + proteinKcal + fatKcal
        // An empty or macro-free week divides by zero. 0/0/0 is the honest answer: nothing was
        // eaten, so nothing is attributable. Filling in a default split would show the user a
        // confident 45/25/30 donut for a week they did not log.
        if (totalKcal <= 0.0) return MacroSplit(0, 0, 0)
        return MacroSplit(
            carbsPct = ((carbsKcal / totalKcal) * 100).toInt(),
            proteinPct = ((proteinKcal / totalKcal) * 100).toInt(),
            fatPct = ((fatKcal / totalKcal) * 100).toInt()
        )
    }

    // ─── Neutral values for the catch paths ─────────────────────────

    private fun emptySummary(date: LocalDate) = DailySummary(
        dateLabel = date.dayLabel(),
        consumed = Nutrition(),
        goalKcal = DEFAULT_GOAL_KCAL,
        entries = emptyList()
    )

    private fun emptyWeek(weekStart: LocalDate) = WeeklyReport(
        weekRangeLabel = weekStart.weekRangeLabel(),
        averageKcalPerDay = 0,
        bestDayLabel = null,
        activeStreakDays = 0,
        macroSplit = MacroSplit(0, 0, 0),
        days = (0 until 7).map { offset ->
            DayTotal(label = weekStart.plusDays(offset.toLong()).dayOfWeek.toShortLabel(), kcal = 0)
        },
        goalKcal = DEFAULT_GOAL_KCAL
    )

    companion object {
        /**
         * Fallback goal for a `null` goals row. Mirrors `NutritionGoals.kcalPerDay` rather than
         * inventing a number, so a user who has not opened "Metas" sees the same target the
         * domain considers the default instead of a second, subtly different one.
         *
         * `val`, not `const val`: `NutritionGoals().kcalPerDay` is a default argument, not a
         * compile-time constant, and a test that changes the domain default should move this
         * value with it instead of freezing a copy that silently drifts.
         */
        val DEFAULT_GOAL_KCAL: Int = NutritionGoals().kcalPerDay

        /** Monday-to-Sunday, per `WeeklyReport`'s seven-bar contract. */
        private const val DAYS_IN_WEEK = 7.0

        private const val MILLIS_PER_DAY_UTC = 86_400_000L

        /**
         * Device-local placeholder until `AuthRepository` exposes the uid.
         *
         * NOT a real identity and NOT a real account: it exists so the goals join has a stable
         * key on a phone where auth has not landed yet. Replace it with the session uid the
         * moment auth exposes one — see the class note.
         */
        const val LOCAL_USER_ID = "local"
    }
}

// ─── Mapping: entity/projection → domain ──────────────────────────

private fun DiaryEntryWithProduct.toDomain(): DiaryEntry = DiaryEntry(
    id = entryId,
    product = FoodProduct(
        barcode = productBarcode,
        name = productName,
        brand = productBrand,
        quantityLabel = productQuantityLabel,
        servingGrams = productServingGrams,
        nutritionPer100g = nutritionPer100g(),
        imageUrl = productImageUrl,
        nutriscoreGrade = productNutriscoreGrade,
        categories = productCategories,
        ingredientsText = productIngredientsText,
        emoji = productEmoji
    ),
    servings = servings,
    mealSlot = mealSlot,
    loggedAtEpochMillis = loggedAtEpochMillis,
    // From the entry's own columns. Recomputing this as
    // `nutritionPer100g.scaled(...)` is the double-rounding bug FoodProduct.nutritionForUnits
    // documents: the number shown would stop matching the number stored.
    totalNutrition = totalNutrition()
)

private fun DiaryEntry.toEntity(dayKey: String): DiaryEntryEntity = DiaryEntryEntity(
    id = id,
    productBarcode = product.barcode,
    servings = servings,
    mealSlot = mealSlot,
    loggedAtEpochMillis = loggedAtEpochMillis,
    dayKey = dayKey,
    kcal = totalNutrition.kcal,
    carbsG = totalNutrition.carbsG,
    proteinG = totalNutrition.proteinG,
    fatG = totalNutrition.fatG,
    sugarsG = totalNutrition.sugarsG,
    fiberG = totalNutrition.fiberG,
    sodiumG = totalNutrition.sodiumG
)

/**
 * Outbox row for [addEntry], built from the domain entry the caller handed us.
 *
 * [dayKey] is passed in rather than recomputed because [addEntry] has already computed it from the
 * same `loggedAtEpochMillis` it used for `diary_entries` — deriving it twice would create the
 * chance of a payload that rolls up into a different remote day document than the entry it belongs
 * to.
 *
 * All seven nutrition values come from `totalNutrition`, the same source as `DiaryEntryEntity`, for
 * the same reason: a payload that reports different numbers than the row it mirrors is worse than no
 * payload, because the server would accept it.
 */
private fun DiaryEntry.toOutboxRow(
    op: SyncOutOp,
    dayKey: String,
    enqueuedAtEpochMillis: Long
): SyncOutboxEntity = SyncOutboxEntity(
    entryId = id,
    op = op.name,
    productBarcode = product.barcode,
    productName = product.name,
    servings = servings,
    mealSlot = mealSlot,
    loggedAtEpochMillis = loggedAtEpochMillis,
    dayKey = dayKey,
    kcal = totalNutrition.kcal,
    carbsG = totalNutrition.carbsG,
    proteinG = totalNutrition.proteinG,
    fatG = totalNutrition.fatG,
    sugarsG = totalNutrition.sugarsG,
    fiberG = totalNutrition.fiberG,
    sodiumG = totalNutrition.sodiumG,
    enqueuedAtEpochMillis = enqueuedAtEpochMillis
)

/**
 * Outbox row for [deleteEntry], built from the projection read *before* the row was removed.
 *
 * This has to be a separate mapping from [toOutboxRow] rather than a shared one, and the reason is
 * the whole design: on the add path the values are still in the caller's hands, but on the delete
 * path they exist only in the joined row that [DiaryDao.withProductById] returned. One mapping fed
 * by two sources would have to invent whichever value was missing, and inventing nutrition on a
 * DELETE means subtracting numbers from the server's day total that never were added to it.
 *
 * `dayKey` is the projection's own column rather than a `LocalDate` recomputation, for the same
 * reason [DiaryEntry.toOutboxRow] takes it as a parameter.
 */
private fun DiaryEntryWithProduct.toOutboxRow(
    op: SyncOutOp,
    enqueuedAtEpochMillis: Long
): SyncOutboxEntity = SyncOutboxEntity(
    entryId = entryId,
    op = op.name,
    productBarcode = productBarcode,
    productName = productName,
    servings = servings,
    mealSlot = mealSlot,
    loggedAtEpochMillis = loggedAtEpochMillis,
    dayKey = dayKey,
    kcal = kcal,
    carbsG = carbsG,
    proteinG = proteinG,
    fatG = fatG,
    sugarsG = sugarsG,
    fiberG = fiberG,
    sodiumG = sodiumG,
    enqueuedAtEpochMillis = enqueuedAtEpochMillis
)

private fun FoodProduct.toEntity(): FoodProductEntity = FoodProductEntity(
    barcode = barcode,
    name = name,
    brand = brand,
    quantityLabel = quantityLabel,
    servingGrams = servingGrams,
    kcalPer100g = nutritionPer100g.kcal,
    carbsPer100g = nutritionPer100g.carbsG,
    proteinPer100g = nutritionPer100g.proteinG,
    fatPer100g = nutritionPer100g.fatG,
    sugarsPer100g = nutritionPer100g.sugarsG,
    fiberPer100g = nutritionPer100g.fiberG,
    sodiumPer100g = nutritionPer100g.sodiumG,
    imageUrl = imageUrl,
    nutriscoreGrade = nutriscoreGrade,
    categories = categories,
    ingredientsText = ingredientsText,
    emoji = emoji
)

private fun DayNutritionTotals.toNutrition(): Nutrition = Nutrition(
    kcal = kcal, carbsG = carbsG, proteinG = proteinG, fatG = fatG,
    sugarsG = sugarsG, fiberG = fiberG, sodiumG = sodiumG
)

private fun DayNutritionTotalsByDay.toNutrition(): Nutrition = Nutrition(
    kcal = kcal, carbsG = carbsG, proteinG = proteinG, fatG = fatG,
    sugarsG = sugarsG, fiberG = fiberG, sodiumG = sodiumG
)

// ─── Day keys and Spanish labels ──────────────────────────────────

/**
 * Zero-padded `yyyy-MM-dd`, matching `DiaryEntryEntity.dayKey`'s load-bearing format.
 *
 * `LocalDate.toString()` already emits exactly this for years 0..9999, and the lexical-range
 * property the weekly query depends on only holds because the padding is there.
 */
private fun LocalDate.toDayKey(): String = toString()

private val SPANISH = Locale.forLanguageTag("es")
private val DAY_ABBREV = DateTimeFormatter.ofPattern("EEE", SPANISH)
private val MONTH_ABBREV = DateTimeFormatter.ofPattern("MMM", SPANISH)

/**
 * Formats a pattern and drops the abbreviation's trailing full stop.
 *
 * CLDR's Spanish abbreviations carry a period ("mar.", "ago.") because Spanish typography
 * abbreviates with a point. On a dense day header that punctuation is noise: it renders as
 * "Mar. 25 Ago." and, uppercased, as "19–25 AGO." — the trailing dot reads as a typo or as the
 * start of a truncated sentence, and the range chip has no sentence to close.
 *
 * Stripping it is a **display** decision taken at the mapping layer rather than by switching to
 * a different locale or pattern, so the underlying locale data stays correct for anything that
 * ever needs the punctuated form.
 */
private fun DateTimeFormatter.abbreviate(date: LocalDate): String =
    format(date).removeSuffix(".")

/** "Mié 25 Ago" — the format `DailySummary.dateLabel` documents. */
private fun LocalDate.dayLabel(): String {
    val weekday = DAY_ABBREV.abbreviate(this).replaceFirstChar { it.uppercase() }
    val month = MONTH_ABBREV.abbreviate(this).replaceFirstChar { it.uppercase() }
    return "$weekday ${dayOfMonth} $month"
}

/**
 * "19–25 AGO" inside one month, "31 AGO – 6 SEP" across two.
 *
 * The month is uppercased because that is the convention the existing design uses for a compact
 * range chip, and a lowercase "ago" next to an uppercase weekday looks like two different
 * typographic systems on the same screen.
 *
 * Both bounds are explicit rather than "first and last day of the month", because a range that
 * crossed a month boundary has to name both months or it is not a range the user can verify.
 */
private fun LocalDate.weekRangeLabel(): String {
    val end = plusDays(6)
    val startMonth = MONTH_ABBREV.abbreviate(this).uppercase(SPANISH)
    val endMonth = MONTH_ABBREV.abbreviate(end).uppercase(SPANISH)
    return if (month == end.month && year == end.year) {
        "$dayOfMonth–${end.dayOfMonth} $startMonth"
    } else {
        "$dayOfMonth $startMonth – ${end.dayOfMonth} $endMonth"
    }
}

/**
 * Bridges `java.time.DayOfWeek` to the domain enum that carries the Spanish single-letter label.
 *
 * `domain.model.DayOfWeek` deliberately shadows the JDK type, so the mapping goes through
 * `valueOf(name)` — the two enums share names by design, which is what makes this total.
 */
private fun java.time.DayOfWeek.toShortLabel(): String =
    DayOfWeek.valueOf(name).short
