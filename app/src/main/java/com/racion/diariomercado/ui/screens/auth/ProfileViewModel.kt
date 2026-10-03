package com.racion.diariomercado.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthErrorMarkers
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
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
    private val authRepository: AuthRepository
) : ViewModel() {

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

    fun onDismissClaimForm() {
        _uiState.update { it.copy(isClaimFormVisible = false, errorMessage = null) }
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
     */
    fun onClaimSubmit() {
        val state = _uiState.value

        // Guarded on the session, not just on the form. The repository tolerates a missing session
        // (it returns a Failure rather than throwing), but calling it here would spend a round trip
        // to be told something this screen already knows.
        if (state.authState !is AuthState.Anonymous) {
            _uiState.update {
                it.copy(errorMessage = "Primero necesitás una sesión de invitado.", isLoading = false)
            }
            return
        }

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
            when (val result = authRepository.promoteToEmailAccount(state.email, state.password)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isClaimFormVisible = false,
                        claimedEmail = state.email,
                        errorMessage = null
                    )
                }
                // isLoading is cleared on this branch too: leaving it true would pin the form behind
                // a spinner with no way to retry.
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessage())
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

    private companion object {
        /**
         * Firebase's own minimum. Matching it here means the local rule never fights the server, and
         * it is the same constant `LoginViewModel` uses.
         */
        const val MIN_PASSWORD_LENGTH = 6
    }
}