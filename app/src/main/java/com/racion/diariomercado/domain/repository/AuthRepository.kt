package com.racion.diariomercado.domain.repository

import com.racion.diariomercado.core.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * What kind of session the app currently holds, if any.
 *
 * It is a three-state sealed interface and not a nullable `User` on purpose: the screens branch on
 * *no session / throwaway session / permanent session*, never on a particular provider's user
 * object, and a `sealed` makes an unhandled state a compile error instead of a runtime `null`
 * deref.
 *
 * The two-state version argued the same thing over a smaller surface, and adding
 * [Anonymous] is precisely what a `sealed` interface is FOR. Under anonymous-first
 * authentication the provider genuinely produces a third kind of session — a real uid with Firestore
 * access and no permanent credential — and collapsing it into "authenticated" would have left every
 * caller with no way to tell the two apart. A boolean would have pushed that question into the
 * screens, where it is answered inconsistently; the enum answers it once, here.
 *
 * Note also that only [Authenticated] and [Anonymous] count as "signed in" for *data access*:
 * both carry a uid and both may read and write Firestore. The third branch exists for exactly one
 * destination — the profile/settings screen, which is the only place that has to offer "claim your
 * account" — and for [AuthRepository.signOut], whose consequences differ per state.
 *
 * Note there is deliberately no "loading" state. [AuthRepository.authState] must emit a value as
 * soon as the persisted session is known, and a screen that has not heard back yet is better
 * modelled by its own local flag than by a global fourth state that every screen would then have
 * to special-case.
 */
sealed interface AuthState {
    /**
     * No session at all. The sign-in / sign-up screens are the only reachable destinations.
     *
     * This is also the emission a failed session read collapses to: the flow promises never to
     * throw, so "we could not find out" is reported as the least-privileged state rather than as an
     * error the user cannot act on.
     */
    data object Unauthenticated : AuthState

    /**
     * A session EXISTS and has a real `uid`, so it reads and writes Firestore normally — but it
     * carries no permanent credential and cannot be restored to a recoverable account.
     *
     * This state is **destructible**, and that is the whole point of distinguishing it:
     * [AuthRepository.signOut] on an anonymous user destroys the `uid` and every document written
     * under it, irreversibly and server-side. Nothing in the app can undo it, which is why the UI
     * has to confirm before calling it.
     *
     * It is a first-class signed-in state, not a degraded one: the user did nothing wrong, they
     * simply have not claimed the account yet.
     */
    data object Anonymous : AuthState

    /**
     * A session with a permanent email+password credential.
     *
     * Obtained either by [AuthRepository.promoteToEmailAccount] — which keeps the same `uid` — or
     * by [AuthRepository.signIn] to an account that already existed. Signing out destroys nothing.
     *
     * The uid lives in the repository, not here — no screen needs it yet.
     */
    data object Authenticated : AuthState
}

/**
 * Authentication and the observable session it produces.
 *
 * Contract:
 * - The session is observable ([authState]) because it changes from *outside* the screen: a token
 *   refresh, a sign-out triggered from another destination, a promotion, or a restored session on
 *   process death all move it without the user touching a form. It is a THREE-state flow, so it
 *   must resolve the anonymous case to [AuthState.Anonymous] and not collapse it into
 *   [AuthState.Authenticated] — see [AuthState] for why that distinction is load-bearing.
 * - The commands are `suspend` and return [AppResult]; a network failure is a value on the
 *   result, never an exception on the caller's coroutine. The UI must be able to render a
 *   message for every one of those cases, and a thrown exception cannot be rendered that way.
 * - The implementation owns credential validation beyond what a `TextField` can express. The
 *   ViewModel still validates the obvious cases (blank, malformed, too short) *before* calling,
 *   because that is a UX decision, not a data-layer one — see `LoginViewModel`.
 *
 * ## The entry points, and why there is more than one
 * The app is anonymous-first: a new user gets a usable session with no form and no account, then
 * claims it later if they want one. That produces three commands, not one:
 * - [signInAnonymously] is the DEFAULT. The first launch calls it before any Firestore read,
 *   because a `uid` is what the security rules and the document paths are keyed on.
 * - [promoteToEmailAccount] adds a credential to the session that already exists, keeping the same
 *   `uid`, so no Firestore data moves.
 * - [signIn] is the RETURNING-USER path for someone who already has an account.
 *
 * [signUp] is superseded by [promoteToEmailAccount] and should be removed once its callers are
 * migrated; see its KDoc.
 *
 * ## Error codes the implementation must map
 * Every `FirebaseAuthException` code below is a distinct user situation and most deserve a distinct
 * message. Collapsing them into one `AppError.Unknown` is what turns a fixable problem into a
 * dead end: "esa cuenta ya existe" and "sin conexión" call for opposite next steps.
 * - `ERROR_EMAIL_ALREADY_IN_USE` — the typed address already belongs to another account. Special
 *   case, see [promoteToEmailAccount].
 * - `ERROR_CREDENTIAL_ALREADY_IN_USE` — the credential is attached to a different account than the
 *   current one (linking, not signing in).
 * - `ERROR_INVALID_EMAIL`, `ERROR_WEAK_PASSWORD` — client-side validation gaps; the ViewModel
 *   should have caught these first, so reaching them is a bug worth logging.
 * - `ERROR_TOO_MANY_REQUESTS` — rate limited, retryable after a backoff.
 * - `ERROR_NETWORK_REQUEST_FAILED` — no connectivity, retryable.
 * - `ERROR_USER_DISABLED`, `ERROR_USER_NOT_FOUND`, `ERROR_WRONG_PASSWORD`,
 *   `ERROR_OPERATION_NOT_ALLOWED` — the account is unusable or the provider is not configured for
 *   this flow; each needs its own honest message.
 * - `ERROR_ANONYMOUS_USER_FIRED` — the anonymous user was deleted upstream, so the session's uid no
 *   longer exists. The only correct response is to drop to [AuthState.Unauthenticated].
 */
interface AuthRepository {

    /**
     * The current session, re-emitting on every change.
     *
     * The returned flow must never complete and never throw: a collector that has to wrap
     * `collect` in `try/catch` and handle a `CompletionException` will eventually get it wrong,
     * and "we could not read the session" is [AuthState.Unauthenticated] as far as the UI is
     * concerned, not an error state the user can act on.
     *
     * It must distinguish [AuthState.Anonymous] from [AuthState.Authenticated]; see [AuthState].
     */
    val authState: Flow<AuthState>

    /**
     * Creates the anonymous session the whole app starts from. **This is the default entry point.**
     *
     * It is a real network call against the Firebase provider and it can fail, so a failure has to
     * be a value on the [AppResult] — there is no local session to fall back on and no cache to
     * hide behind.
     *
     * It MUST be called before any Firestore read or write, not just before the first screen: a
     * Firestore call with no `currentUser` is denied by the security rules, and the resulting
     * permission error looks like a rules problem rather than a missing sign-in.
     *
     * Returns [AppResult] of [Unit] for the same reason [signIn] does: the resulting session state
     * is read from [authState], not from the return value, so a success carrying a user object
     * would be a second source of truth for the same fact.
     */
    suspend fun signInAnonymously(): AppResult<Unit>

    /**
     * Claims the CURRENT anonymous account by attaching an email+password credential to it.
     *
     * This links the credential to the existing `FirebaseUser` rather than creating a new account,
     * and the linked user **keeps the same `uid`**. The Firebase documentation is explicit that
     * "the user's permanent account retains access to all Firebase data from their anonymous
     * session", so on the happy path **no Firestore document moves and no merge is required** —
     * which is precisely why anonymous-first was safe to adopt.
     *
     * ## The one case that is NOT free
     * If the address the user typed already belongs to a different account, the link fails with
     * `ERROR_EMAIL_ALREADY_IN_USE` and the caller has to reconcile two unrelated data trees.
     * **v1 deliberately does not build that merge.** The caller must instead surface an explicit,
     * honest message telling the user that account already exists and that they should sign in
     * with it. This is a documented non-goal, not an oversight: a silent partial merge would be
     * worse than a refusal, and the happy path — the only one that matters to a user who typed
     * *their own* address for the first time — is free.
     *
     * Other codes it must map: `ERROR_INVALID_EMAIL` and `ERROR_WEAK_PASSWORD` (both should have
     * been caught by ViewModel validation), `ERROR_NETWORK_REQUEST_FAILED`,
     * `ERROR_TOO_MANY_REQUESTS`, and `ERROR_CREDENTIAL_ALREADY_IN_USE` if the credential turns out
     * to be attached elsewhere.
     *
     * Only valid while [authState] is [AuthState.Anonymous]. On [AuthState.Authenticated] there is
     * nothing to promote; on [AuthState.Unauthenticated] there is no session to attach to, and
     * linking a credential to a null user is not a no-op but a crash.
     */
    suspend fun promoteToEmailAccount(email: String, password: String): AppResult<Unit>

    /**
     * Signs in to an account that ALREADY exists. This is the returning-user path, the one a user
     * takes when the email they type is not the email they are creating.
     *
     * It is not how a new user gets an account: they get an anonymous session from
     * [signInAnonymously] and claim it with [promoteToEmailAccount], which keeps their data.
     *
     * Returns [AppResult] of [Unit]: the session afterwards is read from [authState], not from
     * the return value, so a "success" that carries a `User` object would be a second source of
     * truth for the same fact.
     *
     * Codes: `ERROR_WRONG_PASSWORD` and `ERROR_USER_NOT_FOUND` (the provider will not say which),
     * `ERROR_USER_DISABLED`, `ERROR_TOO_MANY_REQUESTS`, `ERROR_NETWORK_REQUEST_FAILED`.
     */
    suspend fun signIn(email: String, password: String): AppResult<Unit>

    /**
     * Creates a brand-new account, destroying any session the caller had.
     *
     * SUPERSEDED by [promoteToEmailAccount], which is the ratified way to obtain an email account
     * under anonymous-first. This member is kept only because `LoginViewModel`, `RegisterScreen`
     * and their tests still call it and they are another contributor's lane; removing it is a
     * known follow-up cleanup, once those callers move to promotion and the register form either
     * becomes the "claim your account" form or is retired.
     *
     * Do not build anything new on it. Two ways to create an account is exactly how the two drift
     * apart: the register path cannot see the anonymous data that the promotion path preserves, so
     * the same app would quietly produce users who lose their diary by picking the "wrong" screen.
     *
     * A provider that rejects a duplicate address maps that to
     * [com.racion.diariomercado.core.AppError.Server], not to a thrown exception, so the screen
     * can tell "already registered" from "no connection".
     */
    suspend fun signUp(email: String, password: String): AppResult<Unit>

    /**
     * Ends the session. Signing out when nobody is signed in is a no-op success, not a failure:
     * the observable end state the user cares about is already correct.
     *
     * ## WARNING: this is destructive on an anonymous session
     * Signing out while [authState] is [AuthState.Anonymous] **destroys the `uid` and every
     * Firestore document written under it, permanently and server-side.** There is no recovery, no
     * undo and no backup path in the app. The UI MUST confirm with the user before calling this in
     * that state, and the confirmation has to say what is actually lost — a generic "¿Cerrar
     * sesión?" makes a destructive action look like a reversible one.
     *
     * On [AuthState.Authenticated] it is ordinary: the account survives and can be signed into
     * again, which is exactly the difference the two states exist to express.
     */
    suspend fun signOut(): AppResult<Unit>
}
