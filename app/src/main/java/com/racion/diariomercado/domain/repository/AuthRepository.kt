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
     * The uid itself lives in the repository; the only thing that reads it today is the anonymous
     * claim flow, via [currentUid], and it does so to re-key local rows rather than to render it.
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
 * - `ERROR_INVALID_ID_TOKEN`, `ERROR_TOKEN_EXPIRED` — a Google token that is malformed or stale.
 *   Specific to [signInWithGoogle], and retryable by fetching a fresh token rather than by
 *   retrying the same one.
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
     * The current session's `uid`, or `null` when there is no session.
     *
     * ## Why this is here, when [AuthState] deliberately keeps the uid private
     * It used to be private with the note "no screen needs it yet". One does now: reclaiming an
     * anonymous account into one that **already exists** cannot be a link — Firebase refuses to link
     * a credential that belongs to another account — so the flow signs in, the uid changes, and the
     * rows keyed by the OLD uid have to be re-keyed. Capturing the old uid therefore has to happen
     * BEFORE the sign-in, because afterwards it is gone.
     *
     * `ProfileViewModel` is that caller. `SessionDataReassignerTest` and `ProfileViewModelTest` pin
     * the ordering, because reading it late yields `x -> x`, moves zero rows, and loses the user's
     * settings with no visible error anywhere.
     *
     * ## It is a snapshot, not a stream
     * Read it twice, not once, when you need before-and-after: it changes under you on a successful
     * sign-in. It is also not a second source of truth for "is there a session" — [authState] is.
     */
    val currentUid: String?

    /**
     * Signs in with Google, **replacing whatever session is active** — including an anonymous one.
     *
     * ## Read this before calling it: it destroys the anonymous session
     * This is [signInWithGoogle] with the anonymous-session protection removed, and that protection
     * is the only thing standing between the user and an irreversible server-side deletion. When
     * Firebase signs in over an anonymous account it **deletes that account**, together with every
     * document written under its `uid`. Nothing in the app can undo it. See the warning on
     * [signOut] — this reaches the same destruction by a route the user never asked to confirm.
     *
     * Today the loss is empty, because Firestore is still stubs and nothing writes server-side. That
     * is a property of the current build, not of this operation, and it stops being true the moment
     * DB-7 sync lands. **Do not call this from a code path that has not asked the user.**
     *
     * ## Why it is a separate method rather than a parameter
     * The alternative was `signInWithGoogle(idToken, replaceSession = true)`. A boolean that
     * silently doubles the destructiveness of a call is one somebody flips in a hurry; a distinct
     * name makes the cost visible at every call site, and `signInWithGoogle`'s own KDoc can keep
     * saying "safe".
     *
     * ## What the caller owes the user, and what it owes itself
     * - Ask first. This is the only reason the method is public rather than private: the caller
     *   needs the seam to put a dialog in front of it.
     * - Re-key afterwards. This call moves the uid, so anything keyed by it has to follow. Read
     *   [currentUid] BEFORE calling, because afterwards the old value is gone — see [currentUid] and
     *   [SessionDataReassigner]. Skipping this orphans the user's profile and goals.
     */
    suspend fun signInWithGoogleReplacingSession(idToken: String): AppResult<Unit>

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
     * `ERROR_EMAIL_ALREADY_IN_USE` — Firebase will not attach a credential that already belongs
     * somewhere else, and there is no "turn account A into account B" in Firebase Auth. The caller
     * must therefore offer [signIn] as a second path rather than treat this as a dead end.
     *
     * **This is no longer a data-loss situation.** An earlier version of this note refused the case
     * outright because it would have meant reconciling two Firestore trees. It is not one: the
     * local schema keeps the diary in tables with no `userId` column at all, so signing into the
     * existing account moves nothing but two small rows, which [SessionDataReassigner] re-keys.
     * `ProfileViewModel` wires the two paths together.
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
     * Signs in with a Google-issued OIDC **ID token**, and CLAIMS the current anonymous account
     * when there is one.
     *
     * ## Why the token, and not a credential object
     * The parameter is a `String`. Obtaining a Google credential requires showing a system account
     * picker, which needs an `Activity` — something a repository must never hold, because an
     * `Activity` reference outlives the screen that should have released it. So the picker runs in
     * the Presentation layer and hands the token over as a plain value, which is what keeps
     * Domain free of both `androidx.credentials` and any Firebase credential type. The
     * implementation builds the `AuthCredential` itself.
     *
     * ## Why "sign in" is a lie when a session already exists
     * `FirebaseAuth.signInWithCredential` on an account that is currently **anonymous** does not
     * upgrade it: it REPLACES the session, which destroys the anonymous `uid` and every Firestore
     * document written under it, permanently and server-side. The user would tap a login button
     * and silently lose the diary they have been keeping since first launch.
     *
     * So this command reads the current session and **links** the Google credential when there is
     * an anonymous account to keep. `linkWithCredential` returns the SAME `FirebaseUser` with the
     * SAME `uid` — the identical mechanism that makes [promoteToEmailAccount] free — so no
     * document moves and no merge is needed. It is the same trade the anonymous-first strategy
     * already made for email, applied to the provider people actually use.
     *
     * With no current user it is an ordinary sign-in, and with a permanent credential already in
     * place Firebase answers `ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL`, which the caller
     * should treat as "you are already signed in".
     *
     * ## The one case that is NOT free
     * If that Google account belongs to somebody else, the link fails with
     * `ERROR_CREDENTIAL_ALREADY_IN_USE` and the caller has to reconcile two unrelated data trees.
     * **v1 deliberately does not build that merge** — see the non-goal on
     * [promoteToEmailAccount], which this command inherits unchanged. The credential arrives as
     * [AuthErrorMarkers.CREDENTIAL_ALREADY_IN_USE] so the caller can say so honestly instead of
     * asking the user to retry a tap that can never succeed.
     *
     * Codes: `ERROR_INVALID_ID_TOKEN` / `ERROR_TOKEN_EXPIRED` (a stale token; the caller should
     * fetch a fresh one and try once more), `ERROR_OPERATION_NOT_ALLOWED` and
     * `ERROR_PROVIDER_NOT_ENABLED` (see [AuthErrorMarkers.PROVIDER_DISABLED] — a console step,
     * not a user error), `ERROR_NETWORK_REQUEST_FAILED`, `ERROR_TOO_MANY_REQUESTS`.
     */
    suspend fun signInWithGoogle(idToken: String): AppResult<Unit>

    /**
     * Signs in with Google **directly**, without linking to any existing anonymous session.
     *
     * This is the primary entry point for users who already have a Google account.
     * It does NOT preserve anonymous data — use this for fresh Google sign-ins.
     *
     * Returns [AppResult] of [Unit]: the session afterwards is read from [authState].
     *
     * Codes: `ERROR_INVALID_ID_TOKEN` / `ERROR_TOKEN_EXPIRED` (stale token),
     * `ERROR_OPERATION_NOT_ALLOWED` / `ERROR_PROVIDER_NOT_ENABLED` (Google not enabled in console),
     * `ERROR_NETWORK_REQUEST_FAILED`, `ERROR_TOO_MANY_REQUESTS`.
     */
    suspend fun signInWithGoogleOnly(idToken: String): AppResult<Unit>

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

/**
 * The one error distinction the auth flows cannot express through [com.racion.diariomercado.core.AppError]
 * alone, given the taxonomy as ratified.
 *
 * ## Why this object exists at all
 * [com.racion.diariomercado.core.AppError] has five members and none of them means "that credential
 * belongs to a different account". That situation is not cosmetic: it is the one case where the only
 * correct next step is to sign in with the other account, and every other message sends the user
 * somewhere useless. See the `v1 non-goal` note on [AuthRepository.promoteToEmailAccount] — v1 does
 * not merge the two data trees, it has to *say* so.
 *
 * `AppError.Server` can carry a human-readable `message`, but a Presentation-layer file matching on
 * the CONTENT of a Spanish sentence is a smell: an editor fixes the wording and the branch silently
 * stops firing. So the marker travels as a stable, language-independent token, and the Data layer
 * writes it while the Presentation layer reads it.
 *
 * ## Why it lives in Domain
 * Both layers need it and neither may import the other. Data maps a provider error code onto it;
 * Presentation switches on it to pick a sentence. Domain is the only layer both already depend on, so
 * this is where a shared token has to live — importing `data.firebase` from a ViewModel would invert
 * the dependency direction and make the Firebase choice unreplaceable, which is the exact thing
 * `AppContainer`'s KDoc is guarding against.
 *
 * Additive only: no member of [AuthRepository] or [AuthState] changed, so implementations and test
 * fakes are unaffected.
 */
object AuthErrorMarkers {
    /**
     * Marker for "the email or credential is already attached to a different account". Covers
     * `ERROR_EMAIL_ALREADY_IN_USE` (the typed address is taken) and
     * `ERROR_CREDENTIAL_ALREADY_IN_USE` (the same situation seen from the credential's side).
     *
     * Deliberately NOT the Firebase code strings: those are a provider's vocabulary, and leaking them
     * into the Presentation layer would make swapping Firebase for another provider a UI-breaking
     * change. This token is ours.
     */
    const val CREDENTIAL_ALREADY_IN_USE = "auth.credential_already_in_use"

    /**
     * Marker for "the Google account you picked already exists, and you are currently anonymous".
     *
     * ## Why this is NOT [CREDENTIAL_ALREADY_IN_USE]
     * The same Firebase code, `ERROR_CREDENTIAL_ALREADY_IN_USE`, arrives from two places that need
     * opposite answers:
     *
     * - `promoteToEmailAccount` on an address that is taken → the user typed their own address for
     *   the first time. There is a second path on screen ("ya tengo cuenta"); point them at it.
     * - `signInWithGoogle` attempting to LINK a credential that is already attached elsewhere →
     *   the user picked an account they already own. The only way in is to sign into it, which
     *   **ends the anonymous session**, so the screen has to ask first.
     *
     * Overloading one marker for both forced the second case into the first case's copy, which on an
     * anonymous session reads as advice the user cannot follow from that screen: "esa cuenta ya
     * está registrada, iniciá sesión con ella" — they ARE trying to.
     *
     * ## It is a question, not an error
     * The Presentation layer must render a dialog for it and never an error message. Anything that
     * reaches the generic error path will invite a retry that can never succeed, because retrying
     * the same tap produces the same code forever.
     */
    const val GOOGLE_ACCOUNT_EXISTS = "auth.google_account_exists"

    /**
     * Marker for "the email or password does not match an account". `ERROR_WRONG_PASSWORD` and
     * `ERROR_USER_NOT_FOUND` share it on purpose — telling the user which one it was discloses
     * whether an address is registered, and they cannot act on the difference.
     */
    const val WRONG_CREDENTIALS = "auth.wrong_credentials"

    /**
     * Marker for "this sign-in method is switched off in the Firebase console".
     *
     * Covers `ANONYMOUS_LOGIN_DISABLED` and `OPERATION_NOT_ALLOWED`, which are the same situation
     * seen from two providers.
     *
     * ## Why this one matters more than it looks
     * It is a **configuration** failure, not a user error: the app is fine, the network is fine, the
     * credentials were never even checked — nobody ever asked Firebase to check them. It is also the
     * single most likely first failure, because enabling the providers in
     * `Authentication → Sign-in method` is a manual web-console step that the code cannot do for
     * itself and that a fresh project ships with switched off.
     *
     * Left unmapped it collapses into `AppError.Unknown`, and the user is told "something unexpected
     * happened" — which is not actionable and sends them looking for a bug in the app instead of a
     * toggle in a browser. It is worth its own message.
     */
    const val PROVIDER_DISABLED = "auth.provider_disabled"
}
