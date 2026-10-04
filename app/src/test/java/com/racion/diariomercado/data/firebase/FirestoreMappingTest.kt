package com.racion.diariomercado.data.firebase

import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MacroSplit
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.model.SportFocus
import com.racion.diariomercado.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the document to domain mapping and nothing else.
 *
 * ## Why this suite is deliberately plain JVM
 * These functions touch no Firestore type, so this class runs without Robolectric and stays in the
 * fast tier. Do not add an SDK import here: `FirebaseFirestoreException.Code` has a static
 * initialiser that builds a status table in `android.util.SparseArray`, which throws
 * `ExceptionInInitializerError` the moment this class is run on a bare JVM. That is not a
 * hypothetical — it is why the error translation lives in `FirestoreErrorMappingTest` under
 * Robolectric.
 *
 * The bugs this suite is defending against are all of the same shape: the mapping compiles, the
 * build is green, and the damage only shows up on a second device holding an older enum name.
 */
class FirestoreMappingTest {

    // --- Goals: document -> domain ------------------------------------------------------------

    @Test
    fun `an absent goals document yields the domain defaults`() {
        assertEquals(NutritionGoals(), goalsFromDocument(null))
    }

    @Test
    fun `an empty goals document yields the domain defaults`() {
        assertEquals(NutritionGoals(), goalsFromDocument(emptyMap()))
    }

    @Test
    fun `goals survive a document round trip unchanged`() {
        val original = NutritionGoals(
            targetWeightKg = 71.5f,
            currentWeightKg = 79.25f,
            kcalPerDay = 2250,
            activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY),
            macroSplit = MacroSplit(carbsPct = 40, proteinPct = 35, fatPct = 25)
        )

        assertEquals(original, goalsFromDocument(original.toGoalsDocument()))
    }

    /**
     * Firestore has ONE numeric type. A document written with an `Int` comes back as a `Double`,
     * because the wire format has no integer. Reading with a plain `as Int` cast throws
     * `ClassCastException` on the first real device, long after the build went green.
     */
    @Test
    fun `integer fields are read back from the doubles firestore actually returns`() {
        val document = mapOf(
            "kcalPerDay" to 1900.0,
            "macroSplit" to mapOf("carbsPct" to 45.0, "proteinPct" to 25.0, "fatPct" to 30.0)
        )

        val goals = goalsFromDocument(document)

        assertEquals(1900, goals.kcalPerDay)
        assertEquals(45, goals.macroSplit.carbsPct)
        assertEquals(25, goals.macroSplit.proteinPct)
        assertEquals(30, goals.macroSplit.fatPct)
    }

    @Test
    fun `float weight fields tolerate an integral value from firestore`() {
        val goals = goalsFromDocument(mapOf("targetWeightKg" to 63.0, "currentWeightKg" to 70.0))

        assertEquals(63f, goals.targetWeightKg, 0.001f)
        assertEquals(70f, goals.currentWeightKg, 0.001f)
    }

    /**
     * Persisted by enum NAME. The one-letter `DayOfWeek.short` labels are presentation, and storing
     * them would mean a copy change ("M" for "Miércoles" becoming something else) silently
     * invalidates every user's saved training days.
     */
    @Test
    fun `active days are persisted by enum name and never by the one letter label`() {
        val document = NutritionGoals(activeDays = setOf(DayOfWeek.WEDNESDAY)).toGoalsDocument()

        assertEquals(listOf("WEDNESDAY"), document["activeDays"])
    }

    @Test
    fun `active days round trip by name`() {
        val original = NutritionGoals(activeDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY))

        val restored = goalsFromDocument(original.toGoalsDocument())

        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), restored.activeDays)
    }

    /**
     * An empty set is a real answer — "I train on no days" — and must not be widened back to the
     * default of every day. That widening would be silent: the screen would light up seven
     * switches the user had turned off.
     */
    @Test
    fun `an explicitly empty set of active days stays empty instead of becoming every day`() {
        val document = NutritionGoals(activeDays = emptySet()).toGoalsDocument()

        assertEquals(emptySet<DayOfWeek>(), goalsFromDocument(document).activeDays)
    }

    /**
     * A name this build does not know about is skipped, not fatal. Renaming or adding an enum
     * constant has to be a compatible change for data already on the server, otherwise one release
     * makes every user's goals unreadable.
     */
    @Test
    fun `an unknown active day name is skipped rather than crashing the read`() {
        val document = mapOf("activeDays" to listOf("MONDAY", "SOMETHING_FROM_THE_FUTURE", "SUNDAY"))

        val restored = goalsFromDocument(document)

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), restored.activeDays)
    }

    @Test
    fun `a malformed active days value falls back to the defaults`() {
        val document = mapOf("activeDays" to "MONDAY")

        assertEquals(NutritionGoals().activeDays, goalsFromDocument(document).activeDays)
    }

    @Test
    fun `a partially written goals document keeps defaults for the missing fields`() {
        val goals = goalsFromDocument(mapOf("kcalPerDay" to 2100))

        assertEquals(2100, goals.kcalPerDay)
        assertEquals(NutritionGoals().targetWeightKg, goals.targetWeightKg, 0.001f)
        assertEquals(NutritionGoals().macroSplit, goals.macroSplit)
    }

    // --- Profile: document -> domain ----------------------------------------------------------

    @Test
    fun `an absent profile document is null because a fresh install is a real state`() {
        assertNull(profileFromDocument(uid = "uid-1", data = null))
    }

    @Test
    fun `the profile survives a document round trip unchanged`() {
        val original = UserProfile(
            userId = "uid-1",
            displayName = "Juli",
            sportFocus = SportFocus.CICLISMO,
            currentWeightKg = 72.5f
        )

        val restored = profileFromDocument(uid = "uid-1", data = original.toProfileDocument())

        assertEquals(original, restored)
    }

    /**
     * `userId` is the document path, not a stored field. Writing it into the document would create
     * a second source of truth for the identity that can disagree with the path it was read from.
     */
    @Test
    fun `userId comes from the authenticated uid and is never stored in the document`() {
        val document = UserProfile(userId = "uid-1", displayName = "Juli").toProfileDocument()

        assertFalse(document.containsKey("userId"))
        assertEquals("uid-1", profileFromDocument(uid = "uid-1", data = document)?.userId)
        assertEquals("uid-2", profileFromDocument(uid = "uid-2", data = document)?.userId)
    }

    @Test
    fun `sport focus is persisted by enum name`() {
        val document = UserProfile(userId = "u", sportFocus = SportFocus.PERDIDA_PESO)
            .toProfileDocument()

        assertEquals("PERDIDA_PESO", document["sportFocus"])
    }

    @Test
    fun `an unknown sport focus name falls back to the neutral default`() {
        val restored = profileFromDocument(uid = "u", data = mapOf("sportFocus" to "BUDDHA"))

        assertEquals(SportFocus.MANTENIMIENTO, restored?.sportFocus)
    }

    @Test
    fun `a profile with no stored sport focus falls back to the neutral default`() {
        val restored = profileFromDocument(uid = "u", data = mapOf("displayName" to "Juli"))

        assertEquals(SportFocus.MANTENIMIENTO, restored?.sportFocus)
        assertEquals("Juli", restored?.displayName)
    }

    @Test
    fun `a document with no display name reads back as an empty name not a crash`() {
        val restored = requireNotNull(profileFromDocument(uid = "u", data = mapOf("currentWeightKg" to 70.0)))

        assertEquals("", restored.displayName)
        assertEquals(70f, restored.currentWeightKg, 0.001f)
    }

    // --- Onboarding consent -------------------------------------------------------------------

    @Test
    fun `onboarding is incomplete when the document is absent`() {
        assertFalse(onboardingCompletedFromDocument(null))
    }

    @Test
    fun `onboarding is complete only when the flag says so`() {
        assertTrue(onboardingCompletedFromDocument(mapOf("completed" to true)))
        assertFalse(onboardingCompletedFromDocument(mapOf("completed" to false)))
    }

    @Test
    fun `onboarding ignores an absent flag inside an existing document`() {
        assertFalse(onboardingCompletedFromDocument(mapOf("somethingElse" to true)))
    }
}
