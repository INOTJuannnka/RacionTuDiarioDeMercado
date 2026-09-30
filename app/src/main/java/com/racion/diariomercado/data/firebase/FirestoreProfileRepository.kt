package com.racion.diariomercado.data.firebase

import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.UserProfile
import com.racion.diariomercado.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow

/**
 * [ProfileRepository] backed by Cloud Firestore.
 *
 * ## Expected document layout (FF-5)
 * ```
 * users/{uid}
 *   ├─ profile               -> { displayName, sportFocus, currentWeightKg }
 *   └─ onboarding/completed  -> { completed: true }
 * ```
 * `sportFocus` is stored as the enum **name** (`"RUNNING"`), never the Spanish title — the
 * title is presentation and would break the stored value on a copy edit.
 *
 * Note that the onboarding flag is a **subdocument** rather than a field inside `profile`.
 * Consent has to be writable on its own: a user who accepts the disclaimer and then fails to
 * save their profile must still be recorded as consented, and a profile write must never be
 * able to grant or revoke consent as a side effect.
 *
 * TODO(FF-4): the `uid` comes from Firebase Auth. Anonymous sign-in is the DECIDED strategy, not
 * an open question — see *Decisions to make*, item 2 in `docs/ROADMAP.md`. Data written under the
 * anonymous `uid` is safe to write, because claiming the account with `linkWithCredential` keeps
 * the same `uid` and nothing moves. The one case that is not free is the user typing an address
 * that already belongs to another account; v1 does not merge the two trees, it tells the user to
 * sign in with that account instead.
 *
 * TODO(FF-5): implement with `DocumentReference` snapshot listeners converted to `Flow`s.
 */
internal class FirestoreProfileRepository : ProfileRepository {

    /**
     * TODO(FF-5): snap to `users/{uid}/profile` and emit `null` when the document does not
     * exist. `null` is a real first-install state, not an error.
     */
    override fun observeProfile(): Flow<UserProfile?> =
        // TODO(FF-5): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirestoreProfileRepository.observeProfile is not implemented yet (FF-5)"
        )

    /** TODO(FF-5): `set` the `profile` document, mapping [UserProfile.sportFocus] to its name. */
    override suspend fun saveProfile(profile: UserProfile): AppResult<Unit> =
        // TODO(FF-5): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirestoreProfileRepository.saveProfile is not implemented yet (FF-5)"
        )

    /** TODO(FF-5): `set` `users/{uid}/onboarding/completed` to `{ completed: true }`. */
    override suspend fun completeOnboarding(): AppResult<Unit> =
        // TODO(FF-5): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirestoreProfileRepository.completeOnboarding is not implemented yet (FF-5)"
        )

    /**
     * TODO(FF-5): snap to `users/{uid}/onboarding/completed` and emit `false` when absent.
     * This replaces the `SharedPreferences` boolean in `MainActivity`; the first emission
     * decides the start route, so it must come from the local cache, not the network.
     */
    override fun observeOnboardingCompleted(): Flow<Boolean> =
        // TODO(FF-5): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirestoreProfileRepository.observeOnboardingCompleted is not implemented yet (FF-5)"
        )
}
