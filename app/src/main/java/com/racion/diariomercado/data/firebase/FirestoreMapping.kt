package com.racion.diariomercado.data.firebase

import com.google.firebase.firestore.FirebaseFirestoreException
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MacroSplit
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.model.SportFocus
import com.racion.diariomercado.domain.model.UserProfile

/**
 * Translation between Firestore documents and the domain types, with no Firestore client involved.
 *
 * ## Why this file exists at all
 * The repositories next door are thin on purpose: they resolve a document reference, hand the raw
 * snapshot here, and translate failures through [toFirestoreAppError]. Everything that can be wrong
 * in a way the compiler cannot catch lives in this file, and everything here is reachable from a
 * plain JVM test.
 *
 * That split is not cosmetic. The mistakes this code is shaped to avoid are all invisible at build
 * time and destructive in production:
 *
 * - **Every number comes back as a `Double`.** The wire format has no integer type, so a document
 *   written with an `Int` is read as a `Double`. An `as Int` cast compiles and then throws
 *   `ClassCastException` on the first real device.
 * - **Enums are persisted by `name`, never by ordinal and never by a UI label.** An ordinal breaks
 *   the moment a constant is reordered. The one-letter `DayOfWeek.short` labels are presentation,
 *   and storing them means a copy change silently invalidates every user's saved training days.
 * - **An unknown name is skipped, not fatal.** Adding or renaming an enum constant has to be a
 *   compatible change for data already on the server, or one release makes saved goals unreadable.
 * - **A missing field falls back to the domain default**, per field, so a partially written document
 *   still yields a usable object instead of throwing.
 */

/** Reads [key] as a [Number], tolerating the `Double` Firestore returns for every numeric field. */
private fun Map<*, *>.numberAt(key: String): Number? = this[key] as? Number

/** Reads a nested document, which Firestore hands back as a plain map. */
private fun Map<*, *>.mapAt(key: String): Map<*, *>? = this[key] as? Map<*, *>

/**
 * Rebuilds the training-day set from persisted enum names.
 *
 * Returns `null` for a value that is not a list at all, which the caller reads as "use the
 * defaults". A list containing unrecognised names yields the subset it can resolve rather than
 * nothing: losing one day is recoverable, losing the whole document is not.
 */
private fun activeDaysFrom(raw: Any?): Set<DayOfWeek>? {
    val names = raw as? List<*> ?: return null
    val byName = DayOfWeek.entries.associateBy { it.name }
    return names.mapNotNull { byName[it as? String] }.toSet()
}

/**
 * Rebuilds the macro split from the nested `macroSplit` map.
 *
 * Each percentage is resolved independently so a partially written split still produces a usable
 * value. The domain does not validate that the three sum to 100, and neither does this.
 */
private fun macroSplitFrom(raw: Map<*, *>?): MacroSplit? {
    if (raw == null) return null
    val defaults = NutritionGoals().macroSplit
    return MacroSplit(
        carbsPct = raw.numberAt("carbsPct")?.toInt() ?: defaults.carbsPct,
        proteinPct = raw.numberAt("proteinPct")?.toInt() ?: defaults.proteinPct,
        fatPct = raw.numberAt("fatPct")?.toInt() ?: defaults.fatPct
    )
}

// --- Goals ------------------------------------------------------------------------------------

/**
 * Serialises the goals to `users/{uid}/goals`.
 *
 * The whole object is written every time, which is what makes
 * [com.racion.diariomercado.domain.repository.GoalsRepository]'s last-write-wins contract safe: the
 * screen always emits what it loaded, so there is no partial overwrite to reason about.
 */
internal fun NutritionGoals.toGoalsDocument(): Map<String, Any> = mapOf(
    "targetWeightKg" to targetWeightKg,
    "currentWeightKg" to currentWeightKg,
    "kcalPerDay" to kcalPerDay,
    "activeDays" to activeDays.map { it.name },
    "macroSplit" to mapOf(
        "carbsPct" to macroSplit.carbsPct,
        "proteinPct" to macroSplit.proteinPct,
        "fatPct" to macroSplit.fatPct
    )
)

/**
 * Rebuilds the goals from a `users/{uid}/goals` snapshot.
 *
 * An absent document is **not** an error and **not** `null`: [GoalsRepository.observeGoals] is
 * non-nullable precisely so the screen has no empty state to render, so this returns the domain
 * defaults instead.
 */
internal fun goalsFromDocument(data: Map<String, Any>?): NutritionGoals {
    if (data == null) return NutritionGoals()
    val defaults = NutritionGoals()
    return NutritionGoals(
        targetWeightKg = data.numberAt("targetWeightKg")?.toFloat() ?: defaults.targetWeightKg,
        currentWeightKg = data.numberAt("currentWeightKg")?.toFloat() ?: defaults.currentWeightKg,
        kcalPerDay = data.numberAt("kcalPerDay")?.toInt() ?: defaults.kcalPerDay,
        activeDays = activeDaysFrom(data["activeDays"]) ?: defaults.activeDays,
        macroSplit = macroSplitFrom(data.mapAt("macroSplit")) ?: defaults.macroSplit
    )
}

// --- Profile ----------------------------------------------------------------------------------

/**
 * Serialises the profile to `users/{uid}/profile`.
 *
 * `userId` is deliberately absent. It is the document path, so storing it would create a second
 * source of truth for the identity that can disagree with the path the value was read from — and
 * after an anonymous account is claimed into an existing one, the path is the only thing that
 * moved correctly.
 */
internal fun UserProfile.toProfileDocument(): Map<String, Any> = mapOf(
    "displayName" to displayName,
    "sportFocus" to sportFocus.name,
    "currentWeightKg" to currentWeightKg
)

/**
 * Rebuilds the profile from a `users/{uid}/profile` snapshot.
 *
 * `null` means "never written", which is a real state on a fresh install and not a failure —
 * [ProfileRepository.observeProfile] is nullable for exactly that reason. Note the asymmetry with
 * the goals reader: goals fall back to defaults because their flow is non-null, while the profile
 * falls back to `null` because the screen distinguishes "no profile yet" from "a profile with
 * blank fields".
 */
internal fun profileFromDocument(uid: String, data: Map<String, Any>?): UserProfile? {
    if (data == null) return null
    // The domain requires an id on every profile, but only the path's uid is trustworthy here, so
    // the defaults are borrowed from a placeholder that is never persisted.
    val defaults = UserProfile(userId = "")
    return UserProfile(
        userId = uid,
        displayName = data["displayName"] as? String ?: "",
        sportFocus = (data["sportFocus"] as? String)
            ?.let { name -> SportFocus.entries.firstOrNull { it.name == name } }
            ?: SportFocus.MANTENIMIENTO,
        currentWeightKg = data.numberAt("currentWeightKg")?.toFloat() ?: defaults.currentWeightKg
    )
}

// --- Onboarding consent -----------------------------------------------------------------------

/**
 * Reads `users/{uid}/onboarding/completed` as a boolean.
 *
 * Absent, malformed, or missing the flag all mean `false`. Onboarding is consent, and consent
 * defaults to "not given": a document that cannot be understood must never be read as agreement.
 */
internal fun onboardingCompletedFromDocument(data: Map<String, Any>?): Boolean =
    data?.get("completed") as? Boolean ?: false

// --- Error translation ------------------------------------------------------------------------

/**
 * Maps a Firestore status code onto [AppError].
 *
 * Split out from [toFirestoreAppError] so the mapping is testable without constructing a
 * [FirebaseFirestoreException], whose constructors differ across SDK versions.
 */
internal fun firestoreCodeToAppError(
    code: FirebaseFirestoreException.Code,
    message: String?
): AppError = when (code) {
    // The device is offline or the backend is unreachable. Not the server's fault and not a bug,
    // so it must not be reported as one.
    FirebaseFirestoreException.Code.UNAVAILABLE -> AppError.Network

    // Throttled. Distinct from a generic server fault because the remedy is different: back off,
    // do not retry immediately.
    FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> AppError.RateLimited

    FirebaseFirestoreException.Code.NOT_FOUND -> AppError.NotFound

    // Everything else keeps its numeric code and the SDK message. A permission failure in
    // production is almost always a security-rules problem, and the code is what makes that
    // diagnosable instead of a generic "something went wrong".
    else -> AppError.Server(code.value(), message)
}

/**
 * Maps any throwable raised by the Firestore SDK onto [AppError].
 *
 * Only [FirebaseFirestoreException] carries a meaningful status; anything else is genuinely
 * unclassified and keeps its cause so the origin is not lost.
 */
internal fun Throwable.toFirestoreAppError(): AppError = when (this) {
    is FirebaseFirestoreException -> firestoreCodeToAppError(code, message)
    else -> AppError.Unknown(this)
}
