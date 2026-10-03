package com.racion.diariomercado.data.local

import androidx.room.ColumnInfo
import com.racion.diariomercado.domain.model.MealSlot
import com.racion.diariomercado.domain.model.Nutrition

/**
 * One diary row joined with the catalog row it points at — the shape the mapping layer needs to
 * rebuild a domain [com.racion.diariomercado.domain.model.DiaryEntry].
 *
 * ## Why this projection exists at all
 * [com.racion.diariomercado.data.local.entity.DiaryEntryEntity] stores no product name, and that
 * is deliberate: the seven nutrition columns are stored **already scaled for `servings`**, so the
 * diary's `SUM` never has to reach through a join to the catalog. But the domain `DiaryEntry`
 * carries a whole `FoodProduct`, and the meal row on "Inicio" renders its name and emoji. So the
 * read path needs the catalog columns that the write path deliberately does not duplicate.
 *
 * Keeping that join as a **separate projection type** instead of `@Embedded FoodProductEntity`
 * is what stops the two nutrition sets from being confused: this row has both
 * `kcal` (scaled, for the entry) and `kcalPer100g` (unscaled, for the product), and a projection
 * that reused the entity would hand `SUM`-shaped values to a field named `nutritionPer100g`.
 *
 * ## The column list is explicit and positional on purpose
 * Every column is aliased and named. `SELECT e.*, p.*` would be shorter and would break silently
 * the first time either table gains a column, because the duplicate `id`/`barcode` names collide
 * and Room binds by name.
 */
data class DiaryEntryWithProduct(
    // ── diary_entries ──────────────────────────────────────────────
    @ColumnInfo(name = "entryId") val entryId: String,
    val servings: Int,
    val mealSlot: MealSlot,
    val loggedAtEpochMillis: Long,
    val dayKey: String,
    /** Nutrition **for `servings` units** — already scaled, never re-scaled on read. */
    val kcal: Int,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarsG: Double,
    val fiberG: Double,
    val sodiumG: Double,

    // ── food_products ──────────────────────────────────────────────
    @ColumnInfo(name = "productBarcode") val productBarcode: String,
    @ColumnInfo(name = "productName") val productName: String,
    @ColumnInfo(name = "productBrand") val productBrand: String?,
    @ColumnInfo(name = "productQuantityLabel") val productQuantityLabel: String?,
    @ColumnInfo(name = "productServingGrams") val productServingGrams: Double?,
    @ColumnInfo(name = "productKcalPer100g") val productKcalPer100g: Int,
    @ColumnInfo(name = "productCarbsPer100g") val productCarbsPer100g: Double,
    @ColumnInfo(name = "productProteinPer100g") val productProteinPer100g: Double,
    @ColumnInfo(name = "productFatPer100g") val productFatPer100g: Double,
    @ColumnInfo(name = "productSugarsPer100g") val productSugarsPer100g: Double,
    @ColumnInfo(name = "productFiberPer100g") val productFiberPer100g: Double,
    @ColumnInfo(name = "productSodiumPer100g") val productSodiumPer100g: Double,
    @ColumnInfo(name = "productImageUrl") val productImageUrl: String?,
    @ColumnInfo(name = "productNutriscoreGrade") val productNutriscoreGrade: String?,
    @ColumnInfo(name = "productCategories") val productCategories: List<String>,
    @ColumnInfo(name = "productIngredientsText") val productIngredientsText: String?,
    @ColumnInfo(name = "productEmoji") val productEmoji: String
) {
    /**
     * The entry's own nutrition, rebuilt from the seven flattened columns.
     *
     * It comes from the entry columns and **never** from `nutritionPer100g * servings`: that
     * second path is the double-rounding bug `FoodProduct.nutritionForUnits` documents, where the
     * number the user saw stopped matching the number the diary kept. The stored value is the
     * value.
     */
    fun totalNutrition(): Nutrition = Nutrition(
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        sugarsG = sugarsG,
        fiberG = fiberG,
        sodiumG = sodiumG
    )

    /** The catalog product, rebuilt for display. Nutrition stays per 100 g. */
    fun nutritionPer100g(): Nutrition = Nutrition(
        kcal = productKcalPer100g,
        carbsG = productCarbsPer100g,
        proteinG = productProteinPer100g,
        fatG = productFatPer100g,
        sugarsG = productSugarsPer100g,
        fiberG = productFiberPer100g,
        sodiumG = productSodiumPer100g
    )
}

/**
 * One row of the per-day aggregate over a week range: the seven `SUM` columns grouped by
 * [dayKey], plus the count.
 *
 * ## Why the query groups instead of running seven equality reads
 * `WeeklyReport.days` always has seven bars, but a quiet week has two or three days with data.
 * Seven `dayTotals(dayKey)` reads would work and would cost seven index lookups on every
 * emission; one `GROUP BY dayKey` over the same indexed range returns only the days that have
 * rows, and the mapping layer fills the gaps with real zeroes. The zeroes are the caller's job
 * because "no rows for this day" is a mapping concern, not a SQL one — and it has to be an
 * explicit zero rather than a missing bar, or the chart silently changes its x-axis.
 */
data class DayNutritionTotalsByDay(
    val dayKey: String,
    val kcal: Int,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarsG: Double,
    val fiberG: Double,
    val sodiumG: Double,
    /** Entries that contributed to this day's sums. */
    val entryCount: Int
)
