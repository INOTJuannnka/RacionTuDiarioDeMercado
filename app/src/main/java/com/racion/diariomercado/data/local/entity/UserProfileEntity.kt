package com.racion.diariomercado.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.racion.diariomercado.domain.model.SportFocus
import com.racion.diariomercado.domain.model.UserProfile

/**
 * The minimal user record, one row per user.
 *
 * ## The onboarding flag is deliberately NOT a column here
 * `ProfileRepository` carries `completeOnboarding` / `observeOnboardingCompleted`, and it is
 * tempting to add an `onboardingCompleted: Boolean` here and call the contract served. That would
 * invert the reason the contract exists: onboarding is **consent, not data**, and it must be
 * writable independently so a failed profile write can never un-consent the user. Put the flag in
 * this row and every `saveProfile` becomes a way to reset consent, including the one that writes
 * default weights on a fresh install.
 *
 * Firestore stores it as its own subdocument (`users/{uid}/onboarding/completed`) for exactly
 * this reason, so the local equivalent is a fifth table rather than a column here. That table is
 * not in this work unit — FF-5/FF-7 own the flag's lifecycle, and adding a consent column to a
 * profile row without them would bake the wrong ownership into the schema before anyone has
 * decided where it lives.
 *
 * ## [userId] matches the remote key
 * `users/{uid}` in Firestore, so the two sides of the sync address the same row by the same
 * value.
 *
 * ## Why [currentWeightKg] is duplicated from the goals row
 * `UserProfile` and `NutritionGoals` each declare a current weight independently, and collapsing
 * them into one stored column would mean the repository invents a write to a table the caller
 * never touched. That is the kind of hidden coupling a local source of truth must not have: the
 * duplication is in the domain on purpose, so the storage layer mirrors the domain instead of
 * editorialising it.
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey
    val userId: String,

    /** Empty string, not null: "not set yet" and "set to blank" must not be the same row. */
    val displayName: String = "",

    /**
     * Persisted by `name`, never by `title`/`description`.
     *
     * [SportFocus]'s own KDoc states the trap: the two strings are Spanish and rendered verbatim
     * on the "Perfil deportivo" screen, so they get copy-edited. Keying storage off them would
     * mean a wording change requires a data migration and silently degrades every historical row
     * that used the old text. The enum constant name is the stable identifier.
     */
    val sportFocus: SportFocus = SportFocus.MANTENIMIENTO,

    /** kg. Duplicated from the goals row on purpose — see the class note. */
    val currentWeightKg: Float = 68f
)