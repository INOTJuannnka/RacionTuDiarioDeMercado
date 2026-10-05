package com.racion.diariomercado.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.UserProfile
import com.racion.diariomercado.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [ProfileRepository] backed by Cloud Firestore.
 *
 * ## Document layout (FF-5)
 * ```
 * users/{uid}
 *   ├─ profile               -> { displayName, sportFocus, currentWeightKg }
 *   └─ onboarding/completed  -> { completed: true }
 * ```
 * `sportFocus` is stored as the enum **name** (`"RUNNING"`), never the Spanish title — the
 * title is presentation and would break the stored value on a copy edit. `userId` is not stored
 * at all: it is the document path, and after an anonymous account is claimed into an existing one
 * the path is the only thing that moved correctly. All of that lives in [toProfileDocument] /
 * [profileFromDocument], where it is unit tested.
 *
 * ## Why the onboarding flag is a subdocument rather than a field inside `profile`
 * Consent has to be writable on its own: a user who accepts the disclaimer and then fails to save
 * their profile must still be recorded as consented, and a profile write must never be able to
 * grant or revoke consent as a side effect. This is the same last-write-wins-per-document hazard
 * that [FirestoreGoalsRepository] exists to avoid, applied to the one field where the asymmetry
 * matters most.
 *
 * ## Why the collaborators are lambdas, and why neither has a default
 * Same reason as [FirestoreGoalsRepository], inherited from [FirebaseAuthRepository]: resolve the
 * Firebase handles lazily inside each call so this class never depends on initialisation ordering,
 * because `FirebaseFirestore.getInstance()` throws when `FirebaseApp` is not ready.
 *
 * [currentUid] is narrower than [com.racion.diariomercado.domain.repository.AuthRepository]: this
 * repository needs the uid and nothing else. Neither parameter defaults, so
 * [com.racion.diariomercado.di.AppContainer] remains the one place that decides where auth comes
 * from.
 */
internal class FirestoreProfileRepository(
    private val firestore: () -> FirebaseFirestore,
    private val currentUid: () -> String?
) : ProfileRepository {

    /**
     * Emits the stored profile, or `null` while it has never been written.
     *
     * `null` is a real first-install state, not an error, which is why the type is nullable here
     * while [com.racion.diariomercado.domain.repository.GoalsRepository.observeGoals] is not: the
     * profile screen genuinely distinguishes "no profile yet" from "a profile with blank fields".
     *
     * The failure handling mirrors [FirestoreGoalsRepository.observeGoals] deliberately — same two
     * stages, same fallbacks — because a divergence here would mean one screen silently tolerates
     * a missing session while the other crashes.
     */
    override fun observeProfile(): Flow<UserProfile?> =
        flow {
            val ref = profileReference()
            if (ref == null) {
                emit(null)
                return@flow
            }
            emitAll(
                ref.snapshots().map { snapshot ->
                    // getData(), not data: Kotlin resolves the bare name `data` to the stdlib's
                    // `kotlin.data` DeepRecursiveFunction, which is not a call on the snapshot.
                    profileFromDocument(
                        uid = requireNotNull(currentUid()),
                        data = snapshot.getData()
                    )
                }
            )
        }
            .catch { emit(null) }
            .distinctUntilChanged()

    /**
     * Writes the `profile` document.
     *
     * `set`, not an update: the domain object is the whole document, so there is no field here
     * that a merge would preserve and no patch to get wrong.
     */
    override suspend fun saveProfile(profile: UserProfile): AppResult<Unit> {
        val uid = currentUid() ?: return noSessionFailure("the profile")
        val ref = firestore()
            .collection(USERS)
            .document(uid)
            .collection(PROFILE)
            .document("main")
        return runFirestoreWrite {
            ref.set(profile.toProfileDocument()).awaitTask()
        }
    }

    /**
     * Marks the onboarding consent as accepted.
     *
     * Writes `{ completed: true }` to `users/{uid}/onboarding/completed`.
     */
    override suspend fun completeOnboarding(): AppResult<Unit> {
        val ref = onboardingReference() ?: return noSessionFailure("onboarding consent")
        return runFirestoreWrite {
            ref.set(mapOf(COMPLETED to true)).awaitTask()
        }
    }

    /**
     * Emits whether onboarding was already completed.
     *
     * Listens to `users/{uid}/onboarding/completed` and parses the `completed` flag.
     * If there is no session or a failure occurs, emits `false`.
     */
    override fun observeOnboardingCompleted(): Flow<Boolean> =
        flow {
            val ref = onboardingReference()
            if (ref == null) {
                emit(false)
                return@flow
            }
            emitAll(
                ref.snapshots().map { snapshot ->
                    onboardingCompletedFromDocument(snapshot.getData())
                }
            )
        }
            .catch { emit(false) }
            .distinctUntilChanged()

    /**
     * Ensures a profile document exists for the current user.
     *
     * Reads the profile document; if it doesn't exist, creates a default profile with
     * empty displayName, MANTENIMIENTO sport focus, and default weight. This handles users
     * who sign in with existing Google accounts and skip the onboarding flow.
     */
    override suspend fun ensureProfileExists(): AppResult<Unit> {
        val uid = currentUid() ?: return noSessionFailure("the profile")
        val ref = firestore()
            .collection(USERS)
            .document(uid)
            .collection(PROFILE)
            .document("main")

        val snapshot = ref.get().awaitTask()
        if (snapshot.exists()) {
            return AppResult.Success(Unit)
        }

        val defaultProfile = UserProfile(
            userId = "",
            displayName = "",
            sportFocus = com.racion.diariomercado.domain.model.SportFocus.MANTENIMIENTO,
            currentWeightKg = UserProfile(userId = "").currentWeightKg
        )

        return runFirestoreWrite {
            ref.set(defaultProfile.toProfileDocument()).awaitTask()
        }
    }

    /** `users/{uid}/profile/main`, or `null` when there is no session. */
    private fun profileReference(): DocumentReference? {
        val uid = currentUid() ?: return null
        return firestore()
            .collection(USERS)
            .document(uid)
            .collection(PROFILE)
            .document("main")
    }

    /** `users/{uid}/onboarding/completed`, or `null` when there is no session. */
    private fun onboardingReference(): DocumentReference? {
        val uid = currentUid() ?: return null
        return firestore()
            .collection(USERS)
            .document(uid)
            .collection(ONBOARDING)
            .document(COMPLETED)
    }

    private companion object {
        /** Path segment `users`, used twice: as the collection and as the document key. */
        const val USERS = "users"
        const val PROFILE = "profile"
        const val ONBOARDING = "onboarding"
        const val COMPLETED = "completed"
    }
}
