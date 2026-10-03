package com.racion.diariomercado.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.racion.diariomercado.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * The user record: one row, observed.
 *
 * ## What this DAO deliberately does NOT have
 * `ProfileRepository` also declares `completeOnboarding` and `observeOnboardingCompleted`, and
 * this interface does not implement them. Onboarding is **consent, not data**: the contract
 * requires it to be writable independently so a failed profile write can never un-consent the
 * user, and Firestore keeps it in its own subdocument (`users/{uid}/onboarding/completed`) for
 * the same reason. Squeezing the flag into a column on this row — the obvious way to make the DAO
 * look complete — would make every `saveProfile` a write that can reset consent.
 *
 * The flag needs a table of its own, and where that table's lifecycle lives is FF-5/FF-7's call,
 * not this layer's. Shipping a consent column now would bake in the wrong ownership before anyone
 * has decided it.
 *
 * ## Why `null` is an emission and not an error
 * `ProfileRepository.observeProfile` specifies `null` for "never written" — a fresh install, a
 * real state and not a failure. Room expresses that as a nullable emission, so the collector
 * renders the onboarding route instead of an error screen.
 *
 * ## Last-write-wins
 * `@Upsert` replaces the whole row. `UserProfile` is small and complete by construction (four
 * fields, all with defaults), so there is nothing a merge would preserve.
 */
@Dao
interface ProfileDao {

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    fun observeProfile(userId: String): Flow<UserProfileEntity?>

    @Upsert
    suspend fun saveProfile(profile: UserProfileEntity)
}