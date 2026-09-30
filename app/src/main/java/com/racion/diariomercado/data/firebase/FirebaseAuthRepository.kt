package com.racion.diariomercado.data.firebase

import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [AuthRepository] backed by Firebase Authentication.
 *
 * This is SCAFFOLDING, not an implementation. It exists so the login screens, the [AuthRepository]
 * contract and the navigation graph can be built and reviewed before a single credential is sent
 * anywhere; every method that would touch Firebase is a `NotImplementedError`.
 *
 * ## Why [authState] returns a value while the commands throw
 * [authState] is a one-line `flowOf(AuthState.Unauthenticated)`, which is honest for a stub: with
 * no Firebase session restored, "not signed in" is the correct emission, and it lets the login
 * screens render and be previewed today. The commands have no honest stub value — there is no
 * [AppResult] that means "authenticate later" — so they throw, matching every other Firestore
 * stub in this package.
 *
 * DEVIATION FROM THE CONTRACT, deliberate and temporary: a real `flowOf` **completes** after one
 * emission, while [AuthRepository.authState] promises a flow that never completes. Nothing breaks
 * today because the only consumer would treat completion as "still unauthenticated", but this
 * must be replaced by a real `callbackFlow` over `FirebaseAuth.addAuthStateListener` (FF-4), not
 * kept as-is.
 *
 * TODO(FF-4): implement all five members against `FirebaseAuth.getInstance()`. The order they
 * have to land in is not arbitrary: the anonymous entry point exists so a new user has a `uid`
 * before the first Firestore read, and the promotion step exists so that `uid` can later be
 * claimed **without moving any data**, because `linkWithCredential` preserves it. `signUp` is the
 * superseded path and is kept only until its callers migrate; it is not to be extended.
 */
internal class FirebaseAuthRepository : AuthRepository {

    /**
     * TODO(FF-4): bridge `FirebaseAuth.addAuthStateListener` into a `callbackFlow` that emits
     * [AuthState.Authenticated] when the current user has a permanent credential,
     * [AuthState.Anonymous] when `currentUser != null` but `isAnonymous` is true, and
     * [AuthState.Unauthenticated] otherwise, closing with
     * `awaitClose { removeAuthStateListener(listener) }`. The listener MUST be removed on close or
     * it leaks the collector for the lifetime of the process.
     *
     * The three-way split is the whole point: mapping every non-null `currentUser` to
     * [AuthState.Authenticated] would erase [AuthState.Anonymous] and make a throwaway session
     * indistinguishable from a permanent one, which is the exact confusion the state exists to
     * prevent. `ERROR_ANONYMOUS_USER_FIRED` arriving through the listener must land on
     * [AuthState.Unauthenticated] — the uid is gone, so there is nothing left to represent.
     */
    override val authState: Flow<AuthState> = flowOf(AuthState.Unauthenticated)

    /**
     * TODO(FF-4): `FirebaseAuth.getInstance().signInAnonymously()`. This is the DEFAULT entry
     * point: it has to be called before any Firestore read or write, because the security rules
     * and the `users/{uid}` document paths are both keyed on a `currentUser` that does not exist
     * yet. A read attempted first is denied by the rules, and that permission error reads like a
     * rules bug rather than a missing sign-in.
     *
     * It is a real network call against the provider, so it can fail: map
     * `ERROR_NETWORK_REQUEST_FAILED` to [com.racion.diariomercado.core.AppError.Network] and
     * `ERROR_TOO_MANY_REQUESTS` to [com.racion.diariomercado.core.AppError.RateLimited], with the
     * rest falling through to [com.racion.diariomercado.core.AppError.Unknown] carrying the
     * exception for logging only. There is no local fallback here — a failure is a failure, and
     * the caller has to show it rather than quietly continue unsigned.
     */
    override suspend fun signInAnonymously(): AppResult<Unit> =
        // TODO(FF-4): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirebaseAuthRepository.signInAnonymously is not implemented yet (FF-4)"
        )

    /**
     * TODO(FF-4): build `EmailAuthProvider.credential(email, password)` and link it to the CURRENT
     * user with `FirebaseAuth.getInstance().currentUser!!.linkWithCredential(credential)`.
     *
     * Linking rather than creating is the entire reason this app can be anonymous-first: the linked
     * user KEEPS ITS `uid`, so every document already written under the anonymous account stays
     * reachable and **no Firestore data moves**. Do not write this as
     * `createUserWithEmailAndPassword` followed by a copy — that is the expensive version, and it
     * is the thing this design exists to avoid.
     *
     * The `!!` is justified by the contract, not by optimism: the method is only valid while
     * [AuthState.Anonymous]. Guard the call site on [AuthRepository.authState] and let the
     * `!!` document the invariant for anyone who does not.
     *
     * Error mapping, and one code that must NOT be generic:
     * - `ERROR_EMAIL_ALREADY_IN_USE` needs its own case and its own actionable message, NOT a fall
     *   through to [com.racion.diariomercado.core.AppError.Unknown]. It means the address already
     *   belongs to a different account, so the only way forward is to sign in with it — a generic
     *   "unexpected error" sends the user in circles. v1 **deliberately does not merge the two
     *   data trees**; the caller surfaces the honest message instead. See the `v1 non-goal` note on
     *   [AuthRepository.promoteToEmailAccount].
     * - `ERROR_CREDENTIAL_ALREADY_IN_USE` is the same situation seen from the credential's side.
     * - `ERROR_INVALID_EMAIL` / `ERROR_WEAK_PASSWORD` should be unreachable — the ViewModel
     *   validates both first — so map them, but log them: reaching them is a validation gap.
     * - `ERROR_NETWORK_REQUEST_FAILED` -> [com.racion.diariomercado.core.AppError.Network],
     *   `ERROR_TOO_MANY_REQUESTS` -> [com.racion.diariomercado.core.AppError.RateLimited].
     */
    override suspend fun promoteToEmailAccount(email: String, password: String): AppResult<Unit> =
        // TODO(FF-4): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirebaseAuthRepository.promoteToEmailAccount is not implemented yet (FF-4)"
        )

    /**
     * TODO(FF-4): `FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)`,
     * mapping `FirebaseAuthException` error codes onto [com.racion.diariomercado.core.AppError]
     * (`ERROR_NETWORK_REQUEST_FAILED` -> [com.racion.diariomercado.core.AppError.Network],
     * `ERROR_TOO_MANY_REQUESTS` -> [com.racion.diariomercado.core.AppError.RateLimited], and a
     * wrong-credentials code that has no matching case -> [com.racion.diariomercado.core.AppError.Unknown]
     * carrying the exception for logging only).
     *
     * Note that `ERROR_WRONG_PASSWORD` and `ERROR_USER_NOT_FOUND` are deliberately indistinguishable
     * here: reporting which one it was tells an attacker whether an address is registered, and the
     * user cannot act on the difference anyway. One honest message, no detail.
     */
    override suspend fun signIn(email: String, password: String): AppResult<Unit> =
        // TODO(FF-4): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirebaseAuthRepository.signIn is not implemented yet (FF-4)"
        )

    /**
     * TODO(FF-4): `FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)`.
     * `ERROR_EMAIL_ALREADY_IN_USE` is the one that matters for the register screen: it must
     * render "esa cuenta ya existe", not a generic failure, so it needs its own case and its own
     * message rather than falling through to [com.racion.diariomercado.core.AppError.Unknown].
     *
     * SUPERSEDED by [promoteToEmailAccount], which is the ratified way to get an email account and
     * does not orphan the anonymous data. Implement this only to keep `RegisterScreen` compiling
     * until its caller migrates; do not extend it, and expect it to be deleted. See the KDoc on
     * [AuthRepository.signUp].
     */
    override suspend fun signUp(email: String, password: String): AppResult<Unit> =
        // TODO(FF-4): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirebaseAuthRepository.signUp is not implemented yet (FF-4)"
        )

    /**
     * TODO(FF-4): `FirebaseAuth.getInstance().signOut()`. Signing out with no current user is
     * already a no-op there, so it can be reported as a plain success without a pre-check.
     *
     * Nothing in this method has to branch on the session kind — Firebase destroys an anonymous
     * user on signOut the same way either way — but the CALLER must: on an anonymous session this
     * irreversibly destroys the `uid` and all its Firestore data, so the UI has to confirm first.
     * See the warning on [AuthRepository.signOut].
     */
    override suspend fun signOut(): AppResult<Unit> =
        // TODO(FF-4): implement. Note this throws NotImplementedError (an Error, NOT an Exception):
        // the documented "no method may throw" contract applies to REAL implementations, and callers
        // writing `catch (e: Exception)` will not catch this stub. Remove this method body entirely
        // when the implementation lands.
        throw NotImplementedError(
            "FirebaseAuthRepository.signOut is not implemented yet (FF-4)"
        )
}
