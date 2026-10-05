package com.racion.diariomercado.data.local

import com.racion.diariomercado.data.local.entity.FoodProductEntity
import com.racion.diariomercado.data.local.entity.NutritionGoalsEntity
import com.racion.diariomercado.data.local.entity.UserProfileEntity
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.model.SportFocus
import com.racion.diariomercado.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored rows must be indistinguishable from the domain defaults they stand in for.
 *
 * ## Why this needs a test
 * Each entity copies its literals from the domain model, and Kotlin default arguments are
 * invisible to the compiler's callers. Change `NutritionGoals.kcalPerDay` and nothing fails to
 * compile: a brand-new user's goals row keeps serving 1900 while the domain says 2100, the
 * "Metas" slider bound moves, and the discrepancy only shows up as a wrong number on a fresh
 * install. There is no other place in the build where the two would meet.
 *
 * The alternative — reading the literals off `NutritionGoals()` inside the entity — was rejected:
 * it constructs a throwaway object per defaulted row and hides the actual values behind another
 * type's parameter list, so the schema no longer documents itself.
 */
class EntityDefaultsTest {

    private val userId = "test-user"

    @Test
    fun aDefaultGoalsRowMatchesTheDomainDefaults() {
        val entity = NutritionGoalsEntity(userId = userId)
        val domain = NutritionGoals()

        assertEquals(domain.targetWeightKg, entity.targetWeightKg, 0f)
        assertEquals(domain.currentWeightKg, entity.currentWeightKg, 0f)
        assertEquals(domain.kcalPerDay, entity.kcalPerDay)
        assertEquals(domain.activeDays, entity.activeDays)
        assertEquals(domain.macroSplit.carbsPct, entity.carbsPct)
        assertEquals(domain.macroSplit.proteinPct, entity.proteinPct)
        assertEquals(domain.macroSplit.fatPct, entity.fatPct)
    }

    /** The default active set is every ISO weekday, not an arbitrary subset. */
    @Test
    fun theDefaultActiveDaysAreAllSevenWeekdays() {
        assertEquals(7, NutritionGoalsEntity(userId = userId).activeDays.size)
    }

    @Test
    fun aDefaultProfileRowMatchesTheDomainDefaults() {
        val entity = UserProfileEntity(userId = userId)
        val domain = UserProfile(userId = userId)

        assertEquals(domain.displayName, entity.displayName)
        assertEquals(domain.sportFocus, entity.sportFocus)
        assertEquals(domain.currentWeightKg, entity.currentWeightKg, 0f)
    }

    /** `MANTENIMIENTO` is the "no specific goal" option, so it is the safe fresh-install value. */
    @Test
    fun theDefaultSportFocusIsMantenimiento() {
        assertEquals(SportFocus.MANTENIMIENTO, UserProfileEntity(userId = userId).sportFocus)
    }

    /**
     * The emoji default has to be the *same plate* as the domain default. It is the visual stand-in
     * for a product photo, so a mismatch shows as a generic glyph in one layer and a real one in
     * the other for the same row.
     */
    @Test
    fun theDefaultCatalogEmojiMatchesTheDomainDefault() {
        assertEquals(
            product(barcode = "7790000000000", name = "Test").emoji,
            FoodProduct(barcode = "7790000000000", name = "Test", nutritionPer100g = NUTRITION).emoji
        )
    }

    /** Categories default to "ungraded", not to a list with one empty string in it. */
    @Test
    fun theDefaultCatalogRowHasNoCategoriesAndNoBrand() {
        val entity = product(barcode = "7790000000000", name = "Test")

        assertEquals(emptyList<String>(), entity.categories)
        assertEquals(null, entity.brand)
        assertEquals(null, entity.nutriscoreGrade)
    }

    private companion object {
        val NUTRITION = Nutrition(kcal = 100)

        /**
         * The seven per-100 g columns have **no** defaults, deliberately: `Nutrition` defaults
         * everything to zero because a *running total* with a missing field is recoverable, but a
         * catalog row missing a macronutrient is a mapping bug that would quietly present as
         * "this product contains no protein" forever. The compiler should catch it instead.
         */
        fun product(barcode: String, name: String) = FoodProductEntity(
            barcode = barcode,
            name = name,
            kcalPer100g = 100,
            carbsPer100g = 10.0,
            proteinPer100g = 5.0,
            fatPer100g = 2.0,
            sugarsPer100g = 1.0,
            fiberPer100g = 0.5,
            sodiumPer100g = 100.0
        )
    }
}