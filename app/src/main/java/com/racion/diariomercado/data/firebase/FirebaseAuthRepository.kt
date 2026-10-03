package com.racion.diariomercado.data.firebase

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.android.gms.tasks.Task
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthErrorMarkers
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [AuthRepository] backed by Firebase Authentication.
 *
 * The whole anonymous-first strategy lives in this file, and it is two Firebase facts doing all
 * the work:
 * - `signInAnonymously()` mints a real `uid` immediately, with no form, so a new user can read and
 *   write Firestore on the very first launch.
 * - `linkWithCredential` attaches a credential to that same `FirebaseUser` and **keeps the uid**,
 *   so claiming the account later moves no document and needs no merge. That is the entire reason
 *   the app could be anonymous-first at all.
 *
 * ## No constructor argument, on purpose
 * This takes no [FirebaseAuth] and calls `FirebaseAuth.getInstance()` through [firebaseAuth]
 * internally. `AppContainer.authRepository` is written as `FirebaseAuthRepository()` and that call
 * site does not change.
 *
 * The handle is resolved LAZILY, inside each method, never in a constructor or a property
 * initialiser. `getInstance()` throws `IllegalStateException` when `FirebaseApp` has not been
 * initialised — which is exactly the state the module is in until FF-2/FF-3 land — and resolving it
 * eagerly would make merely *constructing* the repository throw, taking down every screen that only
 * wants to render. Resolved per call, the same missing-initialisation situation becomes a per-call
 * failure that [authState] already knows how to absorb and that the commands can report as an
 * [AppResult].
 *
 * ## No `.await()`, on purpose
 * [awaitTask] below bridges `Task` to `suspend` by hand instead of using
 * `kotlinx.coroutines.tasks.await`. That extension lives in `kotlinx-coroutines-play-services`,
 * which is a **separate artifact** from `kotlinx-coroutines-android` and is NOT on this module's
 * classpath — only `implementation(libs.kotlinx.coroutines.android)` is
 * (`app/build.gradle.kts`). Writing `import kotlinx.coroutines.tasks.await` here is a compile error,
 * not a runtime one, so the hand-rolled bridge is what keeps FF-4 inside its lane. If that artifact
 * is ever added, delete [awaitTask] and use `.await()`; nothing else has to change.
 *
 * ## Why [Task] is imported from `com.google.android.gms.tasks`
 * `com.google.firebase.tasks.Task` is only a deprecated *typealias* for the GMS class, published in
 * the separate `com.google.firebase:firebase-tasks` artifact. This module has `play-services-tasks`
 * (a transitive dependency of `firebase-auth`) but not `firebase-tasks`, so the Firebase-flavoured
 * import does not resolve while the GMS one is the identical type. Verified against
 * `:app:dependencies --configuration debugCompileClasspath`.
 */
internal class FirebaseAuthRepository : AuthRepository {

    /**
     * The session, re-emitting on every change, resolving the THREE states the contract requires.
     *
     * ## How the callbackFlow works
     * Firebase's `addAuthStateListener` is a *push* API: it hands you a callback and there is no
     * coroutine to suspend. `callbackFlow` is the adapter for exactly that shape. Its block runs
     * once, on collection, and gives a `ProducerScope` whose `trySend` pushes a value downstream; the
     * block itself is then expected to suspend, and it does so on `awaitClose`.
     *
     * `awaitClose { ... }` is what makes the flow's lifetime honest. It suspends the producer forever
     * and runs its lambda **when the collector goes away** — cancellation, a `take(1)`, the ViewModel
     * being cleared. That is the only correct place to call `removeAuthStateListener`, because the
     * listener holds a strong reference to the collector: without the removal it leaks for the
     * lifetime of the process, and Firebase keeps firing into a scope nobody reads.
     *
     * It is also what stops the flow from **completing**. A `callbackFlow` that returns instead of
     * suspending closes the channel, and a closed channel completes the flow — the exact defect the
     * old `flowOf(AuthState.Unauthenticated)` stub had. `awaitClose` is not optional bookkeeping
     * here; it is the reason `authState` upholds "never completes".
     *
     * ## Why it cannot throw or complete
     * Three independent things enforce the contract:
     * - `.catch { emit(AuthState.Unauthenticated) }` turns ANY upstream failure — including a
     *   synchronous `IllegalStateException` from `getInstance()` when Firebase is not initialised,
     *   which `callbackFlow` rethrows into the collector — into an ordinary *value*. The interface
     *   promises the flow never throws, so "we could not find out" has to arrive as the
     *   least-privileged state, not as an exception the screen has to catch.
     * - `awaitClose` prevents completion.
     * - `.distinctUntilChanged()` drops repeats. `AuthState`'s members are `data object`s, so they
     *   compare by identity, and Firebase fires the listener again for changes this flow does not
     *   model (a token refresh, a `displayName` write). Without this, every one of those recomposes
     *   every collector for nothing.
     *
     * Note `ERROR_ANONYMOUS_USER_FIRED` needs no special case here: the provider fires the listener
     * with a `null` `currentUser` when it revokes an anonymous account, which is the same thing it
     * does on an ordinary sign-out, and [toAuthState] already maps `null` to
     * [AuthState.Unauthenticated]. There is no state left to represent, so there is nothing else to
     * do.
     */
    override val authState: Flow<AuthState> = callbackFlow {
        val auth = firebaseAuth()

        val listener = FirebaseAuth.AuthStateListener { authInstance ->
            // trySend, not send: it cannot suspend, and more importantly it cannot throw on a closed
            // channel. The listener can fire once more during teardown, after the collector has
            // gone, and a throwing send there would surface as an exception nobody is left to catch.
            trySend(authInstance.currentUser.toAuthState())
        }

        auth.addAuthStateListener(listener)

        // Suspends the producer indefinitely; the removal runs on cancellation. See the KDoc above.
        awaitClose { auth.removeAuthStateListener(listener) }
    }
        .catch { emit(AuthState.Unauthenticated) }
        .distinctUntilChanged()

    /**
     * Mints the anonymous session the app starts from. See [AuthRepository.signInAnonymously] for
     * why this has to run before any Firestore call.
     *
     * A failure here is NOT swallowed: there is no local session to fall back on, so the caller has
     * to show it rather than continue unsigned and discover the problem later as a security-rules
     * permission error on the first read.
     */
    override suspend fun signInAnonymously(): AppResult<Unit> = runAuthCall {
        firebaseAuth().signInAnonymously().awaitTask()
    }

    /**
     * Claims the CURRENT account by linking an email+password credential to it.
     *
     * `linkWithCredential`, not `createUserWithEmailAndPassword`. Linking returns a `FirebaseAuthResult`
     * whose `FirebaseUser` is the SAME instance, with the SAME uid — the credential is *attached to*
     * the account rather than a new account being minted, so every document already written under
     * the anonymous uid stays reachable and nothing is copied. Creating a second account and
     * migrating to it is the expensive version of this, and the one this design exists to avoid.
     *
     * The guard is a real `?:`, not the `!!` the stub's KDoc suggested, because the exception this
     * class is written to be robust against is exactly the one a `!!` would throw here. See the
     * class KDoc on lazy resolution: on an uninitialised Firebase, `currentUser` is unreachable and
     * the honest answer is a value, not a crash. It is reported as
     * [AppError.Unknown] because a missing session is an invariant violation on the caller's side —
     * [ProfileViewModel] gates the call on [AuthState.Anonymous] — not a server condition.
     */
    override suspend fun promoteToEmailAccount(
        email: String,
        password: String
    ): AppResult<Unit> {
        val currentUser = runCatching { firebaseAuth().currentUser }.getOrNull()
            ?: return AppResult.Failure(AppError.Unknown(cause = null))

        return runAuthCall {
            val credential = EmailAuthProvider.getCredential(email, password)
            currentUser.linkWithCredential(credential).awaitTask()
        }
    }

    /**
     * Signs in to an account that already exists — the returning-user path.
     *
     * [ERROR_EMAIL_ALREADY_IN_USE] and [ERROR_CREDENTIAL_ALREADY_IN_USE] both mean "that credential
     * belongs to somebody else", which is the single case `promoteToEmailAccount` cannot serve; both
     * map to the one marker [AppError.Server] carries for it, so the claim screen can point the user
     * at signing in instead of at a dead end.
     *
     * [ERROR_WRONG_PASSWORD] and [ERROR_USER_NOT_FOUND] deliberately share the generic branch:
     * distinguishing them tells an attacker whether an address is registered, and the user cannot act
     * on the difference anyway.
     */
    override suspend fun signIn(email: String, password: String): AppResult<Unit> = runAuthCall {
        firebaseAuth().signInWithEmailAndPassword(email, password).awaitTask()
    }

    /**
     * Creates a brand-new account. **Superseded** by [promoteToEmailAccount] and kept only because
     * `LoginViewModel` and `RegisterScreen` still call it. See the warning on [AuthRepository.signUp]:
     * do not build anything new on it.
     */
    override suspend fun signUp(email: String, password: String): AppResult<Unit> = runAuthCall {
        firebaseAuth().createUserWithEmailAndPassword(email, password).awaitTask()
    }

    /**
     * Ends the session.
     *
     * Signing out with no current user is already a no-op inside Firebase, so it is reported as a
     * plain success without a pre-check — the end state the caller cares about is already correct.
     *
     * Nothing here branches on the session kind, and that is the trap: Firebase destroys an
     * anonymous account on signOut exactly as it ends a permanent one. The **caller** has to branch.
     * On [AuthState.Anonymous] this irreversibly destroys the uid and every Firestore document under
     * it, so `ProfileScreen` confirms with a dialog that names the loss before it gets here. See the
     * warning on [AuthRepository.signOut].
     */
    override suspend fun signOut(): AppResult<Unit> = runAuthCall {
        firebaseAuth().signOut()
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /**
     * Resolved per call, never eagerly. See the class KDoc: an eager `getInstance()` would make
     * constructing this repository throw while Firebase is uninitialised.
     */
    private fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    /**
     * Runs a Firebase call and folds both outcomes into the [AppResult] the contract mandates.
     *
     * The block returns the *awaited* value, not a [Task]: it is declared without `suspend` only
     * because this function is `inline`, which lets the `awaitTask()` call inside each block sit in the
     * caller's own suspend context. Marking it `suspend` would work identically and say so more
     * honestly, but then the `try` would not wrap the suspension point without a `coroutineScope`, so
     * an inline block is the shape that actually gives the guarantee below: **the call is inside the
     * `try`.** A helper that returned a `Task` and awaited it outside would let a network failure
     * escape the `catch`, which is the one thing the "no method may throw" contract forbids.
     *
     * A raw exception must never escape a repository (see `core/AppResult.kt`), so the `catch` is
     * exhaustive over [Exception]. `getInstance()`'s `IllegalStateException` is included, which is
     * what turns "FF-2 has not landed yet" into a renderable message on the login screen instead of
     * a crash.
     *
     * Note it catches [Exception] and not [Throwable]: the stubs this replaces threw
     * `NotImplementedError`, an `Error`, and a `catch (e: Exception)` would not have caught them.
     * Nothing here throws an `Error`.
     */
    private inline fun <T> runAuthCall(block: () -> T): AppResult<Unit> = try {
        block()
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Failure(e.toAppError())
    }

    /**
     * The THREE-way split, and the reason this flow is not a `User?`.
     *
     * `null` and `isAnonymous` are different facts, not the same one twice: both carry a uid and
     * both read and write Firestore, but only the second can be destroyed by a sign-out the user did
     * not realise was destructive. Collapsing them into `Authenticated` would leave the profile
     * screen with no way to know when to warn, which is the one thing that warning exists for.
     *
     * `else` covers every permanent-credential provider (email/password today, phone and Google
     * later): all of them are recoverable accounts, so one branch is correct and adding a provider
     * does not touch this function.
     */
    private fun FirebaseUser?.toAuthState(): AuthState = when {
        this == null -> AuthState.Unauthenticated
        isAnonymous -> AuthState.Anonymous
        else -> AuthState.Authenticated
    }

    /**
     * Maps a Firebase failure onto the shared taxonomy.
     *
     * Every code that represents a distinct user situation gets its own case, because the message
     * the user reads is the only thing telling them what to do next and "algo salió mal" throws that
     * away. The fall-through keeps the exception for logging only, per `AppError.Unknown`'s contract.
     *
     * ## The two collision cases, and why they carry a marker instead of just a message
     * `ERROR_EMAIL_ALREADY_IN_USE` and `ERROR_CREDENTIAL_ALREADY_IN_USE` are the one case where a
     * generic message is a dead end: the address the user typed already belongs to a DIFFERENT
     * account, and the only way forward is to sign in with it. v1 deliberately does not merge the
     * two data trees (see the non-goal on [AuthRepository.promoteToEmailAccount]) — it has to be
     * *distinguishable* so the claim screen can say so.
     *
     * They arrive as `AppError.Server(message = AuthErrorMarkers.CREDENTIAL_ALREADY_IN_USE)`, and the
     * marker rather than a Spanish sentence is the payload on purpose: `ProfileViewModel` has to
     * branch on this, and matching on the CONTENT of a user-facing string breaks silently the first
     * time someone fixes the wording. See the KDoc on `AuthErrorMarkers` for why the token lives in
     * Domain instead of here.
     *
     * Note [AppError.Server.code] is left `null` throughout: it is an [Int] for an HTTP status, and a
     * Firebase `errorCode` is a `String`. Forcing one into the other would be a lie the type system
     * is there to prevent.
     */
    private fun Throwable.toAppError(): AppError = when {
        this !is FirebaseAuthException -> AppError.Unknown(this)

        errorCode == ERROR_EMAIL_ALREADY_IN_USE ||
            errorCode == ERROR_CREDENTIAL_ALREADY_IN_USE ->
            AppError.Server(code = null, message = AuthErrorMarkers.CREDENTIAL_ALREADY_IN_USE)

        errorCode == ERROR_NETWORK_REQUEST_FAILED -> AppError.Network
        errorCode == ERROR_TOO_MANY_REQUESTS -> AppError.RateLimited

        // A provider that is switched off in the console. Checked BEFORE the email/password cases
        // below on purpose: `OPERATION_NOT_ALLOWED` is what Firebase answers when the *Email/Password*
        // provider is disabled, and mapping it as a generic Server error would tell the user to
        // "try again" about something that cannot succeed on a retry.
        errorCode == ERROR_ANONYMOUS_LOGIN_DISABLED || errorCode == ERROR_OPERATION_NOT_ALLOWED ->
            AppError.Server(code = null, message = AuthErrorMarkers.PROVIDER_DISABLED)

        // Both should have been caught by ViewModel validation first, so reaching them is a gap
        // rather than a user error — hence Unknown, which keeps the exception for the log.
        errorCode == ERROR_INVALID_EMAIL || errorCode == ERROR_WEAK_PASSWORD ->
            AppError.Unknown(this)

        errorCode == ERROR_USER_NOT_FOUND || errorCode == ERROR_WRONG_PASSWORD ->
            AppError.Server(code = null, message = AuthErrorMarkers.WRONG_CREDENTIALS)

        else -> AppError.Unknown(this)
    }
}

/**
 * Bridges a Firebase [Task] to a `suspend` function.
 *
 * This is a local stand-in for `kotlinx.coroutines.tasks.await`. It is the piece Gemini's version
 * silently assumed: that extension is published in `kotlinx-coroutines-play-services`, a separate
 * artifact from `kotlinx-coroutines-android`, and it is not on this module's classpath. Importing
 * it would not compile.
 *
 * `addOnCompleteListener` fires on the main thread once the task settles, which is why the
 * continuation is resumed there and the caller resumes on its own dispatcher. If the coroutine is
 * already cancelled when the task settles, [kotlinx.coroutines.CancellableContinuation.resume]
 * reports a benign `IllegalStateException` about resuming after cancellation rather than corrupting
 * state, so no extra guard is needed on the resume path.
 *
 * Cancellation cannot be pushed into the task: a Firebase write already handed to the network layer
 * is not retractable, so the honest `invokeOnCancellation` block is empty. The task still completes
 * and its result is discarded — which is exactly right for a sign-in, because the *session* is what
 * persists, not the return value, and [AuthRepository.authState] is the source of truth for it.
 */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) {
            continuation.resumeWithException(error)
        } else {
            continuation.resume(task.result)
        }
    }
    // The task cannot be cancelled, so there is nothing to undo here. See the KDoc.
    continuation.invokeOnCancellation { }
}

// --- Firebase error codes, as literals -------------------------------------------------------
// FirebaseAuthException exposes these as constants, but they are not on the public API surface of
// every SDK version, and a string literal that fails to match only degrades to AppError.Unknown
// rather than not compiling. They are private to this file so a rename cannot half-update a caller.
private const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
private const val ERROR_CREDENTIAL_ALREADY_IN_USE = "ERROR_CREDENTIAL_ALREADY_IN_USE"
private const val ERROR_NETWORK_REQUEST_FAILED = "ERROR_NETWORK_REQUEST_FAILED"
private const val ERROR_TOO_MANY_REQUESTS = "ERROR_TOO_MANY_REQUESTS"
private const val ERROR_ANONYMOUS_LOGIN_DISABLED = "ANONYMOUS_LOGIN_DISABLED"
private const val ERROR_OPERATION_NOT_ALLOWED = "OPERATION_NOT_ALLOWED"
private const val ERROR_INVALID_EMAIL = "ERROR_INVALID_EMAIL"
private const val ERROR_WEAK_PASSWORD = "ERROR_WEAK_PASSWORD"
private const val ERROR_USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
private const val ERROR_WRONG_PASSWORD = "ERROR_WRONG_PASSWORD"