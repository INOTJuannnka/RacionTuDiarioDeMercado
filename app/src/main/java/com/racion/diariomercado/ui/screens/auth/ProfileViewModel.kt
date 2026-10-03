package com.racion.diariomercado.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthErrorMarkers
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the account section of the profile screen renders.
 *
 * [authState] is copied in from [AuthRepository.authState] rather than recomputed here: the session
 * changes from outside this screen (a token refresh, a sign-out from another destination), so
 * holding a local guess would let the two disagree.
 *
 * ## Why this is NOT [LoginUiState]
 * The two forms share their validation rules but not their purpose, and [LoginUiState]'s
 * [LoginUiState.isLoggedIn] flag is a *navigation* signal that means nothing here — this screen is
 * already a destination, so nothing is waiting on being told the user got in. Reusing it would carry
 * a dead field and imply a transition that does not happen. The shared rules below are duplicated
 * deliberately rather than extracted: `LoginViewModel` is another contributor's lane, and the
 * DRY version of this is one small `AuthFormValidation` object both files reach for, which is a
 * follow-up that has to touch their test as well. Three duplicated rules is cheaper than a
 * cross-lane merge; a fifth would not be.
 *
 * ## Why [claimedEmail] is nullable and not read back from the repository
 * `AuthState.Authenticated` is a `data object` and deliberately carries **no** email — the KDoc on
 * [AuthState] states the uid lives in the repository and that no screen needs it yet. So the only
 * honest source available inside this lane is what the user typed on this device.
 *
 * The consequence is stated rather than papered over: after a process death [claimedEmail] is `null`
 * and the screen renders [authenticatedTitle] without a line under it. Inventing a placeholder there
 * ("usuario@ejemplo.com") would be worse than showing nothing, because the user cannot tell it apart
 * from a real address and would believe their account is the wrong one. Surfacing the email durably
 * needs a domain change — widening [AuthState.Authenticated], or adding a read to [AuthRepository] —
 * and both are outside the auth-screen lane. See the note in `docs/SPRINT-1.md` D5.
 *
 * Strings here are user-facing copy and are therefore in Spanish; everything else in this file,
 * including this KDoc, is English.
 */
data class ProfileUiState(
    val authState: AuthState = AuthState.Unauthenticated,
    /** Claim-form email. Never sent anywhere except [AuthRepository.promoteToEmailAccount]. */
    val email: String = "",
    /** Claim-form password. */
    val password: String = "",
    /**
     * Whether the claim form is expanded. Only reachable on [AuthState.Anonymous] — there is no
     * account to claim on the other two states, so the affordance is not rendered and a stale `true`
     * has nothing to show.
     */
    val isClaimFormVisible: Boolean = false,
    /** The email this device last claimed the account with, or `null`. See the KDoc above. */
    val claimedEmail: String? = null,
    val isLoading: Boolean = false,
    /**
     * The Google path's own busy flag, and it is NOT the same as [isLoading].
     *
     * [isLoading] means "a request is in flight". This one is true from the tap that opens the
     * account sheet until the Firebase exchange settles — including the stretch where the user is
     * reading an account list and nothing is happening. Merging them would either disable the email
     * form while someone is choosing an account, or leave the Google button tappable under the
     * sheet.
     */
    val isGoogleInProgress: Boolean = false,
    /**
     * Whether to ask before switching an anonymous session into an existing Google account.
     *
     * Not an error. It is the one case where the user has a way in and the app has to ask which way
     * they want it: the picked Google account already exists, so the only route is signing into it,
     * and signing in **ends the guest session** — irreversibly, server-side. See
     * [AuthRepository.signInWithGoogleReplacingSession].
     *
     * A separate flag from [errorMessage] on purpose. Anything routed to the error path invites a
     * retry, and retrying the same Google tap produces the same code forever.
     */
    val showGoogleMergeConfirmation: Boolean = false,
    /**
     * Whether the form is asking for an account the user **already has**.
     *
     * A different call, not a different validation: [onClaimSubmit] promotes (keeps the uid, free)
     * and [ProfileViewModel.onClaimIntoExistingAccount] signs in (changes the uid, then re-keys two
     * rows). The user has to be able to say which one they mean, because Firebase will not discover
     * it for them — it answers "that address is taken" only after the fact.
     */
    val isClaimingExistingAccount: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Whether the destructive sign-out dialog is open.
     *
     * This lives in the state and not in a `remember` inside the screen on purpose: "the dialog
     * appears for [AuthState.Anonymous] and for nothing else" is the invariant this screen exists to
     * get right, and a local flag cannot be asserted from a test. A `remember` would make the single
     * most dangerous branch in the auth flow the one branch nothing can pin down.
     */
    val showDestructiveSignOut: Boolean = false
) {
    /**
     * Whether signing out from here destroys the account and its data.
     *
     * True for [AuthState.Anonymous] only. [AuthState.Unauthenticated] has nothing to destroy, and
     * [AuthState.Authenticated] survives the sign-out and can be signed into again.
     */
    val signOutIsDestructive: Boolean get() = authState is AuthState.Anonymous
}

/**
 * The three-state branch of the profile screen. See `docs/SPRINT-1.md` D5.
 *
 * This is the ONLY screen in the app that branches on the session kind; every other destination
 * delegates to "is there a session or not", because for Firestore access an anonymous uid and a
 * permanent one are identical. The branch earns its keep for one reason: signing out of an anonymous
 * session destroys the uid and every document under it, irreversibly and server-side. Nobody can undo
 * that from inside the app, so the only place it can be prevented is before the call — which means the
 * screen has to know which of the two signed-in states it is looking at.
 *
 * It therefore also owns the rule that a *permanent* account gets an ordinary sign-out: warning about
 * data loss where there is none teaches users to dismiss dialogs without reading them, which is the
 * exact habit that makes the anonymous warning useless.
 */
class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val sessionDataReassigner: SessionDataReassigner
) : ViewModel() {

    /**
     * The Google credential held while the merge question is on screen.
     *
     * Not in [ProfileUiState]: it is a credential, and putting one in a state object that previews,
     * logs and screenshots can reach is how it ends up somewhere it should not be. It lives here
     * for exactly as long as the dialog does, and is nulled on both answers.
     */
    private var pendingGoogleIdToken: String? = null

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        // Collected for the ViewModel's whole life rather than per-screen: the session outlives any
        // one destination, and a `collect` started in a composable stops when it leaves the
        // composition, which would leave the screen rendering the last state it happened to see.
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                _uiState.update { current ->
                    current.copy(
                        authState = state,
                        // The claim form is meaningless once the account is claimed or the session is
                        // gone, so it closes itself instead of surviving into a state that cannot
                        // render it.
                        isClaimFormVisible = current.isClaimFormVisible && state is AuthState.Anonymous,
                        // Likewise the dialog: an open confirmation must not outlive the state that
                        // made it destructive. `state` is the AuthState, not the ProfileUiState, so
                        // the predicate is applied to the emission rather than to `current`.
                        showDestructiveSignOut = current.showDestructiveSignOut &&
                            state is AuthState.Anonymous
                    )
                }
            }
        }

        requestAnonymousSession()
    }

    /**
     * Asks for the anonymous session this screen needs before it can offer anything, and REPORTS the
     * outcome.
     *
     * ## Why this duplicates the bootstrap in `RacionApplication`
     * `RacionApplication.bootstrapAnonymousSession()` makes the same call, and it has to: the `uid`
     * must exist before any Firestore read, and that ordering has to hold even for a user who never
     * opens this screen. Its result, though, can only go to Logcat — an `Application` has no way to
     * render anything, and `authState` cannot distinguish "the sign-in failed" from "there genuinely
     * is no session": both arrive as [AuthState.Unauthenticated].
     *
     * So the user would see a bare "No has iniciado sesión" with no explanation, and the actual cause
     * — most often a provider that nobody enabled in the Firebase console — would be invisible unless
     * they happened to have Logcat open. That is the gap this closes.
     *
     * Calling it twice is safe: `signInAnonymously()` with a session already in place resolves with
     * THAT user instead of minting a second one, so the second call is a cheap round trip that
     * normally succeeds. The alternative — having the Application publish its result into a shared
     * holder the ViewModel reads — is more machinery for a state the repository already exposes.
     */
    private fun requestAnonymousSession() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.signInAnonymously()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = null)
                }
                // Deliberately NOT clearing the message on success: authState flips to Anonymous a
                // moment later and re-renders the whole branch, so there is nothing left to clear.
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    /**
     * Re-requests the anonymous session after a failure. Wired to the "Reintentar" row the
     * [AuthState.Unauthenticated] branch shows when there is an error, which is what makes the
     * provider-disabled message actionable rather than merely informative: the user can fix the
     * console and tap once instead of force-stopping the app.
     */
    fun onRetryAnonymousSignIn() {
        requestAnonymousSession()
    }

    fun updateEmail(email: String) {
        _uiState.update { it.copy(email = email) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    fun onShowClaimForm() {
        _uiState.update { it.copy(isClaimFormVisible = true, errorMessage = null) }
    }

    /**
     * Hides the form and resets which of the two paths was in flight.
     *
     * `isGoogleInProgress` is cleared too, not just `isLoading`: a user can dismiss the sheet while
     * the Google exchange is still running, and leaving the flag true would make every later Google
     * tap a silent no-op for the rest of the screen's life.
     */
    fun onDismissClaimForm() {
        _uiState.update {
            it.copy(
                isClaimFormVisible = false,
                isGoogleInProgress = false,
                isClaimingExistingAccount = false,
                errorMessage = null
            )
        }
    }

    /**
     * Switches the form between "create an account" and "I already have one".
     *
     * Clearing the error is the point: the message the user just read belongs to the OTHER path. A
     * Google failure still showing "esa dirección ya está registrada" while the user now types a new
     * address is the kind of stale state that makes people submit the same wrong thing twice.
     */
    fun onSwitchClaimPath(toExistingAccount: Boolean) {
        if (_uiState.value.isLoading || _uiState.value.isGoogleInProgress) return
        _uiState.update {
            it.copy(isClaimingExistingAccount = toExistingAccount, errorMessage = null)
        }
    }

    /**
     * Starts the Google claim from a tap on the button.
     *
     * The flag goes up HERE, before the launcher is asked for anything, and not in the result
     * callback. `GoogleSignInButton` disables itself while `isGoogleInProgress` is set, and if the
     * flag waited for the callback the button would still be tappable during the entire account
     * sheet — which is precisely when a second tap does nothing visible and looks like a broken
     * button.
     *
     * ## Nothing is touched here
     * No session check, no validation, no repository call. Whether this device even has Google
     * Play Services is not knowable from the app, so the honest answer is to try and let the
     * launcher fail — see [onGoogleSignInProviderUnavailable]. Guarding it with an assumption here
     * would mean guessing, and a guessed "no" hides the button on perfectly good devices.
     */
    fun onGoogleSignInRequested() {
        if (_uiState.value.isGoogleInProgress) return
        _uiState.update { it.copy(isGoogleInProgress = true, errorMessage = null) }
    }

    /**
     * Exchanges a Google ID token for a session. Wired from `GoogleSignInOutcome.Success`.
     *
     * ## The uid is read BEFORE the call, because it might move
     * [AuthRepository.signInWithGoogle] links when the session is anonymous and signs in otherwise.
     * Linking returns the SAME uid; signing into an existing account returns a different one. Both
     * report `Success`, so the return value alone cannot tell them apart — comparing [currentUid]
     * around the call can, and only if the "before" read happens first. Read afterwards and you get
     * `x -> x`, zero rows moved, and a user's calorie target that silently reverts weeks later.
     */
    fun onGoogleSignIn(idToken: String) {
        if (_uiState.value.isLoading) return
        // Captured unconditionally, even on the paths that end in failure: it costs nothing and
        // being wrong here means not re-keying, which is recoverable. Being wrong the other way
        // means moving rows that were never ours.
        val anonymousUid = authRepository.currentUid
        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogle(idToken)) {
                is AppResult.Success -> {
                    val accountUid = authRepository.currentUid
                    rekeyIfSessionMoved(anonymousUid, accountUid)
                    _uiState.update {
                        it.copy(
                            isGoogleInProgress = false,
                            isClaimFormVisible = false,
                            isClaimingExistingAccount = false,
                            showGoogleMergeConfirmation = false,
                            errorMessage = null
                        )
                    }
                }

                // The account exists and the user is anonymous. Ask, do not report: the next step
                // destroys the guest session, and the token has to be held so confirming does not
                // make them pick the account a second time.
                is AppResult.Failure ->
                    if (result.error.isGoogleAccountExists()) {
                        pendingGoogleIdToken = idToken
                        _uiState.update {
                            it.copy(
                                isGoogleInProgress = false,
                                showGoogleMergeConfirmation = true,
                                errorMessage = null
                            )
                        }
                    } else {
                        // The form stays open: a Google failure is often something the user can fix
                        // without leaving this screen, and retyping an email would be worse.
                        _uiState.update {
                            it.copy(isGoogleInProgress = false, errorMessage = result.error.toUserMessage())
                        }
                    }
            }
        }
    }

    /**
     * The user said yes: end the guest session and move the local rows to the account they chose.
     *
     * The uid is read again here, not reused from [onGoogleSignIn]. Between the two calls the
     * dialog was open and something else could have changed the session; and the "before" value
     * this needs is the one from immediately before the switch, which is the only reading that
     * guarantees it is a different uid from the "after" one.
     */
    fun onGoogleMergeConfirmed() {
        val idToken = pendingGoogleIdToken ?: return
        val anonymousUid = authRepository.currentUid
        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogleReplacingSession(idToken)) {
                is AppResult.Success -> {
                    val accountUid = authRepository.currentUid
                    rekeyIfSessionMoved(anonymousUid, accountUid)
                    pendingGoogleIdToken = null
                    _uiState.update {
                        it.copy(
                            isGoogleInProgress = false,
                            isClaimFormVisible = false,
                            isClaimingExistingAccount = false,
                            showGoogleMergeConfirmation = false,
                            errorMessage = null
                        )
                    }
                }

                // The switch failed, so the uid never moved and there is nothing to re-key. The
                // dialog has to close and the user has to be told: a dialog that just vanishes
                // looks like the app ignored them.
                is AppResult.Failure -> {
                    pendingGoogleIdToken = null
                    _uiState.update {
                        it.copy(
                            isGoogleInProgress = false,
                            showGoogleMergeConfirmation = false,
                            errorMessage = result.error.toUserMessage()
                        )
                    }
                }
            }
        }
    }

    /**
     * The user said no. Nothing has happened yet, so nothing has to be undone.
     *
     * The token is dropped on purpose. Keeping it would leave a credential reachable from a screen
     * the user has declined, and the only way to use it is the very call they just refused.
     */
    fun onGoogleMergeDismissed() {
        pendingGoogleIdToken = null
        _uiState.update { it.copy(showGoogleMergeConfirmation = false, errorMessage = null) }
    }

    /**
     * Moves the per-user rows when — and only when — the uid actually changed.
     *
     * Three ways this correctly does nothing, and all three are ordinary:
     * - linking kept the uid, so there is nothing to move (the common case, and the one that must
     *   NOT write, because a pointless re-key of `x -> x` would delete the target row first);
     * - there was no session, so there is no "before" to move from;
     * - the provider reported a uid that matches, which is the same as the first case.
     *
     * Swallowing the re-key's own failure is deliberate and asymmetric: the account IS claimed at
     * this point, and the diary — the thing users actually notice losing — is untouched because it
     * has no `userId` at all. Blocking the screen on a failed profile-row move would show an error
     * over a state the user considers a success, and would suggest their account is not signed in
     * when it is. The cost of getting this wrong is two defaulted rows, recoverable by re-entering
     * them; the cost of the alternative is telling someone their sign-in failed when it did not.
     */
    private suspend fun rekeyIfSessionMoved(anonymousUid: String?, accountUid: String?) {
        if (anonymousUid == null || accountUid == null || anonymousUid == accountUid) return
        sessionDataReassigner.reassign(anonymousUid, accountUid)
    }

    private fun AppError.isGoogleAccountExists(): Boolean =
        this is AppError.Server && message == AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS

    /**
     * The user closed the account sheet without picking anything.
     *
     * An ABORT is not a failure and must not render as one. `GoogleSignInOutcome.Aborted` arrives
     * when the user taps back or swipes the sheet away, which is a decision, not a problem — showing
     * an error there is how "I changed my mind" turns into "this app is broken".
     *
     * The claim form stays open: they were halfway through claiming, and dismissing it because they
     * changed their mind about which Google account to use would throw away the email they had
     * typed. `ProfileViewModelTest.cancellingTheGoogleSheetIsNotAbandoningTheClaim` pins this.
     */
    fun onGoogleSignInCancelled() {
        _uiState.update { it.copy(isGoogleInProgress = false) }
    }

    /**
     * The device cannot run the Google flow at all.
     *
     * This is the branch that has to point somewhere useful. On an emulator without Play Services,
     * or on a device where Google Sign-In was never configured, a Google button is simply a dead
     * control. The answer is the email form — which always works, because it needs no provider
     * beyond the one already in use.
     *
     * The repository is deliberately NOT called. There is no token to exchange and nothing to learn
     * from a round trip that cannot succeed.
     */
    fun onGoogleSignInProviderUnavailable() {
        _uiState.update {
            it.copy(
                isGoogleInProgress = false,
                isClaimingExistingAccount = true,
                errorMessage = "No pudimos abrir Google en este dispositivo. Usá tu correo y contraseña."
            )
        }
    }

    /**
     * Claims the account with [AuthRepository.promoteToEmailAccount], after validating the form.
     *
     * The repository is not reached on an invalid form, for the same reason `LoginViewModel` does it
     * that way: the rules here are about what the user is told, and a call per keystroke would burn
     * Firebase's rate limit for a form that was never going to be submitted. `LoginViewModelTest`
     * asserts the equivalent negative case for sign-in, and the same assertion is what would catch a
     * regression here.
     *
     * The state transition is NOT written locally on success. `promoteToEmailAccount` links the
     * credential to the same `FirebaseUser`, the provider fires the auth-state listener, and
     * [authState] moves to [AuthState.Authenticated] on its own. Setting it here as well would be a
     * second source of truth for a fact the repository already owns, and the two would disagree the
     * first time a link failed after the server had already accepted it.
     *
     * ## Why nothing is re-keyed here
     * `linkWithCredential` returns the SAME `FirebaseUser` with the SAME uid, so there is nothing to
     * move. That is the entire reason this path is free, and it is why calling
     * [sessionDataReassigner] from here would be a pointless write that would look correct in every
     * test. The user has not lost anything by creating an account, so this is the good version of
     * claiming.
     */
    fun onClaimSubmit() {
        claim(
            // Guarded on the session, not just on the form. The repository tolerates a missing
            // session (it returns a Failure rather than throwing), but calling it here would spend a
            // round trip to be told something this screen already knows.
            call = { email, password -> authRepository.promoteToEmailAccount(email, password) },
            claimedEmail = _uiState.value.email
        )
    }

    /**
     * Claims the account into one that **already exists**.
     *
     * This is the path Firebase will not do for us. `linkWithCredential` only attaches a credential
     * that belongs to no other account, so promoting an address that is already registered always
     * fails with `ERROR_EMAIL_ALREADY_IN_USE`. There is no "turn account A into B" in Firebase Auth.
     * The alternative is [AuthRepository.signIn], which mints the existing account's uid and
     * therefore **changes** the uid — and that is the one case where the local rows have to follow.
     *
     * ## The ordering is the whole implementation
     * The old uid is read BEFORE the sign-in, because a successful sign-in replaces it and the old
     * value is gone. Reading it afterwards yields `x -> x`, the re-assigner moves zero rows, and the
     * user's calorie target silently reverts to its default days later with nothing to explain it.
     * `ProfileViewModelTest.theAnonymousUidIsReadBeforeSignInAndTheAccountUidAfterIt` pins this.
     *
     * The diary needs none of this: `diary_entries`, `sync_outbox` and `food_products` have no
     * `userId` column. Only `user_profiles` and `nutrition_goals` move.
     */
    fun onClaimIntoExistingAccount() {
        claim(
            call = { email, password ->
                // Read first, unconditionally: even if the sign-in fails, this is the right value
                // to have captured, and reading it after would be the bug.
                val anonymousUid = authRepository.currentUid
                when (val result = authRepository.signIn(email, password)) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> {
                        val accountUid = authRepository.currentUid
                        if (anonymousUid != null && accountUid != null && anonymousUid != accountUid) {
                            sessionDataReassigner.reassign(anonymousUid, accountUid)
                        } else {
                            // Nothing to move: either there was no anonymous session (guarded
                            // below), or the uid did not actually move. Reporting success is right
                            // — the account IS claimed, there was simply nothing local to re-key.
                            AppResult.Success(Unit)
                        }
                    }
                }
            },
            claimedEmail = _uiState.value.email
        )
    }

    /**
     * Shared body for the two email paths: session guard, validation, the double-tap guard, and the
     * success/failure bookkeeping.
     *
     * They differ only in which repository command they run and in whether the uid moves, so
     * duplicating the guards would guarantee the two drift apart — and the guards are exactly where
     * the dangerous behaviour lives (a second promotion of the same address is the fastest route to
     * Firebase's rate limiter).
     */
    private fun claim(
        call: suspend (email: String, password: String) -> AppResult<Unit>,
        claimedEmail: String
    ) {
        val state = _uiState.value
        // Captured up front, before any update: the error mapper needs the path the user chose, and
        // the failure branch below has already cleared the form flags by the time it runs.
        val claimingExistingAccount = state.isClaimingExistingAccount

        if (state.authState !is AuthState.Anonymous) {
            _uiState.update {
                it.copy(errorMessage = "Primero necesitás una sesión de invitado.", isLoading = false)
            }
            return
        }

        // The double-tap guard. Two claims of the same address in flight at once is the fastest way
        // to get rate-limited, and the resulting message ("demasiados intentos") has nothing to do
        // with anything the user did.
        if (state.isLoading || state.isGoogleInProgress) return

        validateEmail(state.email)?.let { message ->
            _uiState.update { it.copy(errorMessage = message, isLoading = false) }
            return
        }
        validatePassword(state.password)?.let { message ->
            _uiState.update { it.copy(errorMessage = message, isLoading = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = call(state.email, state.password)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isClaimFormVisible = false,
                        isClaimingExistingAccount = false,
                        claimedEmail = claimedEmail,
                        errorMessage = null
                    )
                }
                // isLoading is cleared on this branch too: leaving it true would pin the form behind
                // a spinner with no way to retry. The form also stays OPEN, because a user who just
                // mistyped their password should not have to start over from the beginning.
                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.error.toClaimUserMessage(claimingExistingAccount)
                    )
                }
            }
        }
    }

    /**
     * The single entry point for signing out, and where the branch on session kind lives.
     *
     * On [AuthState.Anonymous] this does NOT sign out: it opens the confirmation, because
     * [AuthRepository.signOut] there destroys the uid and all its Firestore data irreversibly. On
     * [AuthState.Authenticated] it signs out immediately, and on [AuthState.Unauthenticated] it does
     * nothing — there is no session, so there is nothing to end.
     *
     * Routing both states through one function is what makes the warning unbypassable. A screen with
     * two separate buttons wired straight to `signOut()` will eventually grow a third caller that
     * forgets the check.
     */
    fun onSignOutClick() {
        when (_uiState.value.authState) {
            is AuthState.Anonymous ->
                _uiState.update { it.copy(showDestructiveSignOut = true) }

            is AuthState.Authenticated -> signOut()

            AuthState.Unauthenticated ->
                _uiState.update { it.copy(errorMessage = "No hay ninguna sesión activa.") }
        }
    }

    /**
     * The user confirmed the data loss. This is the ONLY path from the dialog to [AuthRepository.signOut].
     *
     * The dialog is dismissed first so it cannot be re-shown over a screen that has already left
     * [AuthState.Anonymous]; the `init` collector would close it anyway, but relying on that leaves
     * a window where a second tap re-opens a dialog describing a state the user is no longer in.
     */
    fun onDestructiveSignOutConfirmed() {
        _uiState.update { it.copy(showDestructiveSignOut = false) }
        signOut()
    }

    /** The user backed out. Nothing is sent; the account and its data are untouched. */
    fun onDestructiveSignOutDismissed() {
        _uiState.update { it.copy(showDestructiveSignOut = false) }
    }

    /** Clears the current error once it has been on screen long enough to read. */
    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * A sign-out failure is a message and nothing else: the session is unchanged, so there is no
     * partial state to represent and no navigation to undo.
     */
    private fun signOut() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.signOut()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, claimedEmail = null, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    /** @return the message to show, or `null` when [email] is worth sending to the repository. */
    private fun validateEmail(email: String): String? = when {
        email.isBlank() -> "Ingresá tu correo"
        // A single "@" check, not a regex — same reasoning and same comment as LoginViewModel: this
        // is a typo guard, not a deliverability test, and a full RFC-shaped pattern rejects valid
        // addresses the provider would accept.
        !email.contains("@") -> "Ingresá un correo electrónico válido"
        else -> null
    }

    /** @return `null` when [password] is long enough, otherwise the message to show. */
    private fun validatePassword(password: String): String? =
        if (password.length < MIN_PASSWORD_LENGTH) {
            "La contraseña debe tener al menos 6 caracteres"
        } else {
            null
        }

    /**
     * The user-facing text for a failure, one honest sentence per case.
     *
     * The two collision cases are switched on the Domain marker rather than on `AppError.Server`
     * alone, because "esa cuenta ya existe, iniciá sesión con ella" and "no pudimos completar la
     * operación" send the user in opposite directions and only the first is true here. The Spanish
     * sentences live HERE, in the layer that renders them, and not in the Data layer that produced
     * the marker — see the KDoc on [AuthErrorMarkers].
     */
    private fun AppError.toUserMessage(): String = when {
        this is AppError.Server && message == AuthErrorMarkers.PROVIDER_DISABLED ->
            "La autenticación no está habilitada en el proyecto de Firebase. " +
                "Alguien tiene que activar los métodos de acceso en Authentication → Sign-in method."

        this is AppError.Server && message == AuthErrorMarkers.CREDENTIAL_ALREADY_IN_USE ->
            "Ese correo ya pertenece a otra cuenta. Iniciá sesión con ella para recuperar tu diario."

        this is AppError.Server && message == AuthErrorMarkers.WRONG_CREDENTIALS ->
            "El correo o la contraseña no coinciden."

        this is AppError.Server -> "No pudimos completar la operación. Intentá de nuevo."
        this is AppError.Network -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        this is AppError.NotFound -> "No encontramos esos datos."
        this is AppError.RateLimited -> "Demasiados intentos. Esperá un momento."
        this is AppError.Unknown -> "Ocurrió un error inesperado. Intentá de nuevo."
        else -> "Ocurrió un error inesperado. Intentá de nuevo."
    }

    /**
     * The user-facing text for a CLAIM failure.
     *
     * Separate from [toUserMessage] because one sentence has to change meaning, and reusing the
     * general mapper would make it wrong on one of the two paths.
     *
     * ## The one case that depends on which path the user chose
     * `ERROR_EMAIL_ALREADY_IN_USE` means two completely different things depending on intent:
     *
     * - Promoting a **new** address that turns out to be taken: the user has an account and did not
     *   know it. There is a real action available on this very screen, so the copy has to point at
     *   it. Telling them to "sign in" — the old wording, inherited from a flow that had nowhere to
     *   send them — would send a user off this screen to accomplish something two taps away.
     * - Signing into an "existing" address that turns out NOT to exist: the user mistyped, or is on
     *   the wrong account. Saying "that address is taken" would be exactly backwards, so this branch
     *   gets its own copy.
     *
     * The marker is the same; only the user's declared intent separates them. That intent lives in
     * [ProfileUiState.isClaimingExistingAccount] and is passed in rather than read from
     * `_uiState` inside the mapper, so the sentence cannot depend on state that a concurrent update
     * may have already changed.
     */
    private fun AppError.toClaimUserMessage(claimingExistingAccount: Boolean): String = when {
        this is AppError.Server && message == AuthErrorMarkers.CREDENTIAL_ALREADY_IN_USE &&
            !claimingExistingAccount ->
            "Ese correo ya tiene una cuenta. ¿Ya te registraste antes? Cambiá a \"Ya tengo cuenta\"."

        // Reaching here means the address was NOT registered, which is the opposite of the marker.
        // Firebase reports one code for both, and this is the branch where guessing wrong actively
        // misleads: the user would go looking for an account they do not have.
        this is AppError.Server && message == AuthErrorMarkers.CREDENTIAL_ALREADY_IN_USE ->
            "No encontramos una cuenta con ese correo. Revisá el correo o creá una cuenta nueva."

        this is AppError.Server && message == AuthErrorMarkers.WRONG_CREDENTIALS ->
            "El correo o la contraseña no coinciden. Tu diario sigue guardado en este dispositivo."

        this is AppError.Server && message == AuthErrorMarkers.PROVIDER_DISABLED ->
            "La autenticación no está habilitada en el proyecto de Firebase. " +
                "Alguien tiene que activar los métodos de acceso en Authentication → Sign-in method."

        this is AppError.Server -> "No pudimos completar la operación. Tu diario sigue guardado."
        this is AppError.Network -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        this is AppError.NotFound -> "No encontramos esos datos. Tu diario sigue guardado."
        this is AppError.RateLimited -> "Demasiados intentos. Esperá un momento."
        this is AppError.Unknown -> "Ocurrió un error inesperado. Intentá de nuevo."
        else -> "Ocurrió un error inesperado. Intentá de nuevo."
    }

    private companion object {
        /**
         * Firebase's own minimum. Matching it here means the local rule never fights the server, and
         * it is the same constant `LoginViewModel` uses.
         */
        const val MIN_PASSWORD_LENGTH = 6
    }
}