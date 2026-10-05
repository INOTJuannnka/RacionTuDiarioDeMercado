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

    /**
     * Re-keys the profile row from one uid to another. Used by the anonymous-account claim flow.
     *
     * The `WHERE` clause is the whole method: an `UPDATE` written without it would re-key EVERY row
     * in the table and hand one user's name and sport focus to another. `SessionDataReassignerTest`
     * pins that case.
     *
     * `userId` is the PRIMARY KEY, so this throws a constraint violation if a row for
     * [newUserId] already exists. The caller deletes that row first — see the collision note on
     * `SessionDataReassigner.reassign` — which is why this method is not expected to be safe to call
     * on its own.
     *
     * @return the number of rows moved, so a caller can tell "moved" from "there was nothing there"
     *   without a second query.
     */
    @Query("UPDATE user_profiles SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignUserId(oldUserId: String, newUserId: String): Int

    /**
     * Drops the anonymous row ONLY when the account being claimed already owns one on this device.
     *
     * ## Why this exists instead of a delete-then-update
     * `userId` is the PRIMARY KEY, so moving the source row onto an occupied target throws. The
     * obvious fix — delete the target, then move the source in — gets the precedence exactly
     * backwards: it makes the anonymous row win, which is the opposite of what a user signing into
     * their own account expects. It would also overwrite whatever that account had configured.
     *
     * So the precedence is stated here instead: if the target row exists, it is the winner and the
     * source is dropped. Written as one statement with `EXISTS` rather than a read followed by a
     * branch, so it cannot be reached with a stale read and needs no extra DAO method.
     *
     * Call this BEFORE [reassignUserId]. Together they express the whole policy: drop the source if
     * the target is taken, otherwise move it.
     *
     * @return the number of rows deleted: 1 if the target was taken, 0 if it was free or the source
     *   had no row.
     */
    @Query(
        """
        DELETE FROM user_profiles
         WHERE userId = :oldUserId
           AND EXISTS (SELECT 1 FROM user_profiles WHERE userId = :newUserId)
        """
    )
    suspend fun deleteSourceIfTargetExists(oldUserId: String, newUserId: String): Int
}