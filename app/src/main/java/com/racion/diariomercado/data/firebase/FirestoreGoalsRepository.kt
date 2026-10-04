package com.racion.diariomercado.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.repository.GoalsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [GoalsRepository] backed by Cloud Firestore.
 *
 * ## Document layout (FF-5)
 * ```
 * users/{uid}/goals   -> { targetWeightKg, currentWeightKg, kcalPerDay,
 *                          activeDays: [string], macroSplit: { carbsPct, proteinPct, fatPct } }
 * ```
 * One document, no subcollections. `activeDays` is stored as the list of enum **names**
 * (`"MONDAY"`, ...), never the one-letter `DayOfWeek.short` labels — the labels are
 * presentation and a copy change must not invalidate stored data. That rule, and every other
 * mapping decision, lives in [toGoalsDocument] / [goalsFromDocument] where it is unit tested.
 *
 * ## Why a separate document from `profile` (FF-6)
 * The goals screen and the profile screen write independently and at very different
 * frequencies. Sharing a document means a "save goals" write can silently overwrite a newer
 * weight the user just typed on the profile, because Firestore's last-write-wins operates on
 * the whole document, not per field.
 *
 * ## Why the collaborators are lambdas, and why neither has a default
 * The container could have passed `FirebaseFirestore.getInstance()` in the constructor, and the
 * earlier TODO in `AppContainer` literally read `FirestoreGoalsRepository(firestore)`. It takes
 * providers instead, for the reason [FirebaseAuthRepository] already discovered the hard way: it
 * resolves its handle lazily inside each method, so this container depends on neither the
 * *timing* nor the *readiness* of Firebase initialisation. `FirebaseFirestore.getInstance()` throws
 * `IllegalStateException` when `FirebaseApp` is not ready, and a manual DI container is exactly
 * where that ordering bug would be hardest to see. Lazy resolution moves the failure to the call
 * that actually needs it, where `.catch` can turn it into a value.
 *
 * [currentUid] is narrower than [com.racion.diariomercado.domain.repository.AuthRepository] on
 * purpose: these repositories need the uid and nothing else, and taking the whole interface would
 * couple them to sign-in, claims and credential merging they never touch.
 *
 * Neither parameter defaults, so [com.racion.diariomercado.di.AppContainer] stays the one place that
 * decides *where auth comes from*. A default would quietly give a second repository its own answer.
 */
internal class FirestoreGoalsRepository(
    private val firestore: () -> FirebaseFirestore,
    private val currentUid: () -> String?
) : GoalsRepository {

    /**
     * Emits the stored goals, or [NutritionGoals] defaults when the document does not exist.
     *
     * ## Why the two-stage flow
     * `flow { ... emitAll(...) }` exists so the **reference resolution** is inside the flow's own
     * builder. Two failure modes have to be handled and they are different:
     *
     * - No session at all (anonymous sign-in failed and the app continued unsigned). There is no
     *   document to listen to, so the defaults are emitted once and the flow completes. This is a
     *   genuine terminal state, not an error: `GoalsRepository.observeGoals` is non-nullable
     *   precisely so the screen has no empty state to render.
     * - A session but no network, or Firebase not initialised. That throws, and [.catch] turns it
     *   into the same defaults emission so the interface's promise that this flow never throws
     *   holds.
     *
     * ## Why `.distinctUntilChanged()`
     * The snapshot listener re-fires on every server commit, including the acknowledgement of a
     * write this screen just made. Without this, saving goals would recompose every collector with
     * a value identical to the one it already holds. Firestore's listener is also registered with
     * the default `MetadataChanges.EXCLUDE`, so pending-to-committed transitions do not fire at
     * all; this covers the remaining data-identical case.
     */
    override fun observeGoals(): Flow<NutritionGoals> =
        flow {
            val ref = goalsReference()
            if (ref == null) {
                emit(NutritionGoals())
                return@flow
            }
            emitAll(
                ref.snapshots().map { snapshot ->
                    // getData(), not data: Kotlin resolves the bare name `data` to the stdlib's
                    // `kotlin.data` DeepRecursiveFunction, which is not a call on the snapshot.
                    goalsFromDocument(snapshot.getData())
                }
            )
        }
            .catch { emit(NutritionGoals()) }
            .distinctUntilChanged()

    /**
     * Writes the whole document, which is what makes last-write-wins safe.
     *
     * The screen always emits the full object it loaded, so there is no partial-overwrite hazard
     * to defend against here: the document is a projection of the domain object, never a patch.
     */
    override suspend fun saveGoals(goals: NutritionGoals): AppResult<Unit> {
        val ref = runCatching { goalsReference() }.getOrNull()
            ?: return noSessionFailure("goals")

        return runFirestoreWrite {
            ref.set(goals.toGoalsDocument()).awaitTask()
        }
    }

    /**
     * `users/{uid}/goals`, or `null` when there is no session.
     *
     * `runCatching` because the uid read and the `getInstance()` call are both outside the flow
     * here and both can throw; the write path has to answer with a `Failure` rather than an
     * exception either way.
     */
    private fun goalsReference(): DocumentReference? {
        val uid = currentUid() ?: return null
        return firestore().collection(USERS).document(uid).collection(USERS).document(GOALS)
    }

    private companion object {
        /** Path segment `users`, used twice: as the collection and as the document key. */
        const val USERS = "users"
        const val GOALS = "goals"
    }
}
