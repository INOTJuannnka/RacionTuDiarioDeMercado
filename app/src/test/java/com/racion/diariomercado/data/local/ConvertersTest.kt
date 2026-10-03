package com.racion.diariomercado.data.local

import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MealSlot
import com.racion.diariomercado.domain.model.SportFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec contracts for [Converters].
 *
 * Deliberately a plain JVM test with no Robolectric and no database: the converters are pure
 * functions, and the one that can silently corrupt data — the categories list — is exactly the
 * one that must not need an emulator, an in-memory database or a query to prove itself.
 *
 * Every case here is a case where the *obvious* implementation passes a smoke test and fails on
 * real data.
 */
class ConvertersTest {

    private val converters = Converters()

    // -- List<String> categories ------------------------------------------------------------

    /**
     * The headline case: one category that contains a comma.
     *
     * Open Food Facts returns `categories` as a single comma-separated string, and the domain model
     * splits it — so "Beverages,Alcoholic beverages" arriving as **one** element is the normal
     * case, not an edge case. A `joinToString(",")` / `split(",")` codec returns two elements
     * here, and that is how a category silently gets split and truncated forever after, with no
     * error anywhere: the value round-trips, it is just the wrong value.
     */
    @Test
    fun categoriesRoundTripASingleCategoryThatContainsAComma() {
        val categories = listOf("Beverages,Alcoholic beverages")

        val restored = converters.toStringList(converters.fromStringList(categories))

        assertEquals(categories, restored)
        assertEquals(1, restored.size)
    }

    /**
     * The full adversarial set in one round-trip: an embedded comma, an empty string, embedded
     * double quotes, and non-ASCII accents. Empty strings are the ones a naive codec loses
     * silently — `"a,,b".split(",")` happens to preserve them, but a codec that filters blanks or
     * that trims will not, and the filter looks like such a reasonable thing to add.
     */
    @Test
    fun categoriesRoundTripCommasEmptyStringsAndQuotesExactly() {
        val categories = listOf(
            "Beverages,Alcoholic beverages",
            "",
            "he said \"organic\" twice",
            "Plats préparés",
            ",leading",
            "trailing,",
            "  padded  "
        )

        val restored = converters.toStringList(converters.fromStringList(categories))

        assertEquals(categories, restored)
    }

    /** Order is content. A cache row whose categories reorder between reads looks like new data. */
    @Test
    fun categoriesRoundTripPreservesOrderAndCount() {
        val categories = listOf("z", "a", "m", "a")

        val restored = converters.toStringList(converters.fromStringList(categories))

        assertEquals(listOf("z", "a", "m", "a"), restored)
    }

    /** The overwhelmingly common case, pinned so a future codec cannot regress it. */
    @Test
    fun emptyCategoriesRoundTripToAnEmptyList() {
        assertEquals(emptyList<String>(), converters.toStringList(converters.fromStringList(emptyList())))
    }

    /**
     * A stored JSON `null` is treated as "no categories", not as a crash: it is a recoverable
     * value, unlike the malformed case, which is left to throw.
     */
    @Test
    fun aJsonNullDecodesToAnEmptyList() {
        assertEquals(emptyList<String>(), converters.toStringList("null"))
    }

    // -- MealSlot ---------------------------------------------------------------------------

    /**
     * Storage keys off `name`. The assertion that makes this a test rather than a comment is the
     * second one: if someone "helpfully" switches the converter to `label`, the round-trip still
     * passes and the first assertion fails.
     */
    @Test
    fun mealSlotIsPersistedByNameNeverByItsSpanishLabel() {
        val stored = converters.fromMealSlot(MealSlot.ALMUERZO)

        assertEquals("ALMUERZO", stored)
        assertNotEquals(MealSlot.ALMUERZO.label, stored)
        assertEquals(MealSlot.ALMUERZO, converters.toMealSlot(stored))
    }

    @Test
    fun everyMealSlotRoundTrips() {
        MealSlot.entries.forEach { slot ->
            assertEquals(slot, converters.toMealSlot(converters.fromMealSlot(slot)))
        }
    }

    /**
     * A name from a different app version resolves to [MealSlot.DESAYUNO] instead of throwing.
     * The reason is in the entity: the kcal totals do not depend on the meal, and crashing would
     * take the whole diary screen down to protect a display-only label.
     */
    @Test
    fun anUnknownMealSlotFallsBackInsteadOfThrowing() {
        assertEquals(MealSlot.DESAYUNO, converters.toMealSlot("BRUNCH"))
    }

    // -- SportFocus -------------------------------------------------------------------------

    @Test
    fun sportFocusIsPersistedByNameNeverByItsDisplayCopy() {
        val stored = converters.fromSportFocus(SportFocus.FUTBOL)

        assertEquals("FUTBOL", stored)
        assertNotEquals(SportFocus.FUTBOL.title, stored)
        assertNotEquals(SportFocus.FUTBOL.description, stored)
        assertEquals(SportFocus.FUTBOL, converters.toSportFocus(stored))
    }

    @Test
    fun everySportFocusRoundTrips() {
        SportFocus.entries.forEach { focus ->
            assertEquals(focus, converters.toSportFocus(converters.fromSportFocus(focus)))
        }
    }

    /** Unknown focus degrades to the documented "no specific goal" default. */
    @Test
    fun anUnknownSportFocusFallsBackToMantenimiento() {
        assertEquals(SportFocus.MANTENIMIENTO, converters.toSportFocus("NATATION"))
    }

    // -- Set<DayOfWeek> ---------------------------------------------------------------------

    /**
     * The encoded set is Monday-first **regardless of the order the caller happened to build it
     * in**. `Set` has no defined iteration order, so this is the assertion that stops the stored
     * bytes from depending on hash order.
     */
    @Test
    fun theDaySetIsEncodedInIsoOrderNotInsertionOrder() {
        val shuffled = setOf(
            DayOfWeek.SUNDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.MONDAY,
            DayOfWeek.FRIDAY
        )

        assertEquals("MONDAY,WEDNESDAY,FRIDAY,SUNDAY", converters.fromDayOfWeekSet(shuffled))
    }

    @Test
    fun theFullDaySetEncodesMondayThroughSunday() {
        val encoded = converters.fromDayOfWeekSet(DayOfWeek.entries.toSet())

        assertEquals("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY", encoded)
    }

    @Test
    fun everyDaySetRoundTrips() {
        val days = DayOfWeek.entries.toSet()

        assertEquals(days, converters.toDayOfWeekSet(converters.fromDayOfWeekSet(days)))
    }

    /** An empty active set is "rest days", a real state, and must not decode as the full week. */
    @Test
    fun anEmptyDaySetRoundTripsToAnEmptySet() {
        assertEquals(emptySet<DayOfWeek>(), converters.toDayOfWeekSet(converters.fromDayOfWeekSet(emptySet())))
    }

    /**
     * An unresolvable weekday is dropped, not fatal: one bad token must not make a user's weight
     * targets unreadable. Losing a checkbox is acceptable; losing the goals row is not.
     */
    @Test
    fun anUnknownDayTokenIsDroppedAndTheRestSurvives() {
        val decoded = converters.toDayOfWeekSet("MONDAY,SAMSTAG,TUESDAY")

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), decoded)
    }

    /**
     * A delimiter codec for enum constants is only safe because the domain is closed and
     * comma-free — this test is what would notice if a constant with a comma ever got in.
     */
    @Test
    fun dayConstantsAreSafeToUseAsDelimitedTokens() {
        assertTrue(
            "a DayOfWeek constant containing a comma would break the delimited codec",
            DayOfWeek.entries.all { ',' !in it.name }
        )
    }
}