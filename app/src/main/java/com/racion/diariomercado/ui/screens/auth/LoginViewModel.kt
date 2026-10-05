package com.racion.diariomercado.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthErrorMarkers
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the login and register screens render, and nothing else.
 *
 * [confirmPassword] lives here even though only the register screen uses it: the form fields are
 * `remember`ed state today, and moving them into a single state holder is what makes the
 * "validate before the repository" rule below testable at all.
 */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    /** Only the register screen reads this. It is never sent anywhere. */
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    /**
     * The Google path's own busy flag, and it is NOT the same as [isLoading].
     *
     * [isLoading] means "a network call is in flight". This one is true from the moment the system
     * account picker opens until the Firebase exchange settles — which includes the stretch where
     * the user is looking at a chooser and no request is happening at all. Merging the two would
     * either disable the email form while the user reads their account list, or leave the Google
     * button tappable during the picker, and both are worse than one extra boolean.
     */
    val isGoogleInProgress: Boolean = false,
    /**
     * Whether to ask before switching an anonymous session into an existing Google account.
     *
     * A user can reach this screen while still anonymous, and a Google account they already own
     * cannot be linked — signing into it is the only way in, and that ends the guest session
     * irreversibly. Not an error: it is a question with a yes and a no. See
     * [AuthRepository.signInWithGoogleReplacingSession].
     */
    val showGoogleMergeConfirmation: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false
)

/**
 * Drives both auth screens from a single [AuthRepository].
 *
 * ## Why validation lives here and not in the repository
 * Every rule in [submit] is a rule about *what the user is told*, so it belongs above the data
 * layer: a repository that silently rejects a four-character password teaches the UI nothing
 * about which field was wrong. The repository is only called once the input is known to be
 * plausible, which is also what keeps `signIn`/`signUp` from being a network call per keystroke.
 *
 * ## Why `isLoggedIn` duplicates [com.racion.diariomercado.domain.repository.AuthState]
 * It is a navigation signal, not a second source of truth: the screens need to react *once*, on
 * the transition, and then leave the destination. The observable [AuthRepository.authState] is
 * the durable one and is what FF-7 will gate the start route on. Keeping this flag local means a
 * process death cannot leave the app on the login screen believing it is signed in.
 *
 * Strings here are user-facing copy and are therefore in Spanish; everything else in this file,
 * including this KDoc, is English.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    /**
     * Present because an anonymous user can reach this screen.
     *
     * For everyone else this dependency is inert: signing in with a fresh account has no local rows
     * to move, and the tests that cover it pass an unused re-assigner. It is here for the one case
     * where the uid DOES change — switching into a Google account that already exists — and it would
     * be a silent orphaning of the user's profile and goals without it. See
     * [AuthRepository.signInWithGoogleReplacingSession].
     */
private val sessionDataReassigner: SessionDataReassigner
) : ViewModel() {

    /**
     * The Google credential held while the merge question is on screen.
     *
     * Deliberately NOT part of [LoginUiState]: that object is what previews, logs and screenshots
     * can reach, and a credential does not belong in any of those. It lives here for exactly as long
     * as the dialog does, and is nulled on both answers.
     */
    private var pendingGoogleIdToken: String? = null
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updateEmail(email: String) {
        _uiState.update { it.copy(email = email) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    fun updateConfirmPassword(confirmPassword: String) {
        _uiState.update { it.copy(confirmPassword = confirmPassword) }
    }

    /**
     * Validates the login form and, only if it passes, calls [AuthRepository.signIn].
     *
     * The repository is not reached on an invalid form — no call, no [isLoading] flag, just the
     * message. This is asserted by the tests, because a version that called first and validated
     * on the response would still *look* correct in the UI while burning a request.
     */
    fun onSignIn() {
        val state = _uiState.value
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
            when (val result = authRepository.signIn(state.email, state.password)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, isLoggedIn = true, errorMessage = null)
                }
                // isLoading is cleared on the failure branch too: leaving it true would pin the
                // form behind a spinner with no way to retry.
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    /**
     * Marks the Google flow as started, before the system account picker is even on screen.
     *
     * It sets a flag and calls nothing: the picker belongs to the Presentation layer (it needs an
     * `Activity`), so this exists purely to disable the buttons while the user is choosing an
     * account, which is exactly the window in which a second tap would open a second picker.
     */
    fun onGoogleSignInRequested() {
        _uiState.update { it.copy(isGoogleInProgress = true, errorMessage = null) }
    }

    /**
     * Clears the flag when the user dismisses the account picker.
     *
     * Deliberately silent. The user did what they meant to do, and the most common way a cancel
     * becomes a bug report is a screen that answers it with "algo salió mal".
     */
    fun onGoogleSignInCancelled() {
        _uiState.update { it.copy(isGoogleInProgress = false) }
    }

    /**
     * Reports that this device cannot do Google sign-in at all: Play Services is missing or
     * outdated, or there is no usable Google account on it.
     *
     * A separate method, not a [AppError] from [onGoogleSignIn], because no token ever existed —
     * the provider was never asked, so there is nothing for [authRepository] to fail at and nothing
     * to report back. That also makes it the only Google branch that never touches the repository,
     * which is what the test asserts: a blank `signInWithGoogle("")` would look equivalent and
     * would spend a round trip to be told nothing.
     *
     * The sentence points at the email form instead of saying "intentá de nuevo", because retrying
     * the exact same tap on the exact same device produces the exact same failure. There is another
     * button on screen, and this is the moment to name it.
     */
    fun onGoogleSignInProviderUnavailable() {
        _uiState.update {
            it.copy(
                isGoogleInProgress = false,
                errorMessage = "No encontramos Google en este dispositivo. " +
                    "Podés iniciar sesión con tu correo y contraseña."
            )
        }
    }

    /**
     * Exchanges a Google ID token for a session.
     *
     * ## The uid is read BEFORE the call, because it might move
     * [AuthRepository.signInWithGoogle] links when the session is anonymous and signs in otherwise.
     * Linking returns the SAME uid; signing into an existing account returns a different one. Both
     * report `Success`, so the return value alone cannot tell them apart — comparing [currentUid]
     * around the call can, and only if the "before" read happens first. Read afterwards and you get
     * `x -> x`, zero rows moved, and a user's calorie target that silently reverts weeks later.
     */
    fun onGoogleSignIn(idToken: String) {
        if (idToken.isBlank()) {
            _uiState.update {
                it.copy(
                    isGoogleInProgress = false,
                    errorMessage = "No pudimos leer tu cuenta de Google. Intentá de nuevo."
                )
            }
            return
        }

        _uiState.update { it.copy(errorMessage = null) }
        val anonymousUid = authRepository.currentUid
        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogle(idToken)) {
                is AppResult.Success -> {
                    val accountUid = authRepository.currentUid
                    rekeyIfSessionMoved(anonymousUid, accountUid)
                    _uiState.update {
                        it.copy(isGoogleInProgress = false, isLoggedIn = true, errorMessage = null)
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
                        _uiState.update {
                            it.copy(isGoogleInProgress = false, errorMessage = result.error.toGoogleUserMessage())
                        }
                    }
            }
        }
    }

    /**
     * The user said yes: end the guest session and move the local rows to the account they chose.
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
                            isLoggedIn = true,
                            showGoogleMergeConfirmation = false,
                            errorMessage = null
                        )
                    }
                }

                is AppResult.Failure -> {
                    pendingGoogleIdToken = null
                    _uiState.update {
                        it.copy(
                            isGoogleInProgress = false,
                            showGoogleMergeConfirmation = false,
                            errorMessage = result.error.toGoogleUserMessage()
                        )
                    }
                }
            }
        }
    }

    /**
     * The user said no. Nothing has happened yet, so nothing has to be undone.
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
     * Swallowing the re-key's own failure is deliberate: the account IS signed in at this point,
     * and the diary — the thing users actually notice losing — is untouched because it has no
     * `userId` at all. Blocking the screen on a failed profile-row move would show an error over a
     * state the user considers a success, and would suggest their account is not signed in when it
     * is.
     */
    private suspend fun rekeyIfSessionMoved(anonymousUid: String?, accountUid: String?) {
        if (anonymousUid == null || accountUid == null || anonymousUid == accountUid) return
        sessionDataReassigner.reassign(anonymousUid, accountUid)
    }

    private fun AppError.isGoogleAccountExists(): Boolean =
        this is AppError.Server && message == AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS

    /**
     * Validates the register form and, only if it passes, calls [AuthRepository.signUp].
     *
     * Adds the password-match rule on top of [onSignIn]'s: a mismatch is impossible to detect
     * server-side, so if it were not checked here the account would be created with a password
     * the user cannot reproduce on the next sign-in.
     */
    fun onSignUp() {
        val state = _uiState.value
        validateEmail(state.email)?.let { message ->
            _uiState.update { it.copy(errorMessage = message, isLoading = false) }
            return
        }
        validatePassword(state.password)?.let { message ->
            _uiState.update { it.copy(errorMessage = message, isLoading = false) }
            return
        }
        if (state.confirmPassword.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Confirmá la contraseña", isLoading = false)
            }
            return
        }
        if (state.confirmPassword != state.password) {
            _uiState.update {
                it.copy(errorMessage = "Las contraseñas no coinciden", isLoading = false)
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.signUp(state.email, state.password)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, isLoggedIn = true, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessage())
                }
            }
        }
    }

    /**
     * Clears the current error. Called by the screen once the message has been on screen long
     * enough to read, so the next attempt starts from a clean form instead of showing a stale
     * complaint next to fields the user has already fixed.
     */
    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** @return the message to show, or `null` when [email] is worth sending to the repository. */
    private fun validateEmail(email: String): String? = when {
        email.isBlank() -> "Ingresá tu correo"
        // A single "@" check, not a regex. A full RFC-shaped pattern rejects valid addresses the
        // provider would accept, and this is a typo guard, not a deliverability test.
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
     * The user-facing text for a failure on the EMAIL path.
     *
     * Every [AppError] case gets its own sentence on purpose: a shared "algo salió mal" for both
     * "no connection" and "server error" tells the user nothing about whether retrying helps,
     * which is the only decision the message exists to inform.
     *
     * The three [AuthErrorMarkers] cases are switched on the Domain marker rather than on
     * `AppError.Server` alone, because each of them is a dead end under the generic sentence:
     * "esa cuenta ya existe" and "sin conexión" send the user in opposite directions. The Spanish
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

        else -> genericUserMessage()
    }

    /**
     * The user-facing text for a failure on the GOOGLE path.
     *
     * Same markers, different sentences, and the difference is not cosmetic: the marker says "this
     * credential belongs to another account" without saying WHICH credential, so the sentence that
     * completes it has to come from the flow that knows. A Google failure rendered as "el correo o
     * la contraseña no coinciden" would name two inputs the user never touched.
     *
     * [AuthErrorMarkers.WRONG_CREDENTIALS] is deliberately absent: a Google token cannot produce
     * it, and inheriting the email wording "on a path that cannot reach it" would only mean a
     * future reader has to work out why the branch is missing.
     */
    private fun AppError.toGoogleUserMessage(): String = when {
        this is AppError.Server && message == AuthErrorMarkers.PROVIDER_DISABLED ->
            "El acceso con Google no está habilitado en este proyecto. " +
                "Alguien tiene que activarlo en Authentication → Sign-in method."

        // GOOGLE_ACCOUNT_EXISTS is handled BEFORE this (shows a dialog, not an error).
        // CREDENTIAL_ALREADY_IN_USE now only appears from email promotion, so it falls to
        // genericUserMessage() which gives the correct copy for that context.
        else -> genericUserMessage()
    }

    /** The five [AppError] cases, one honest sentence each. Shared by both flows above. */
    private fun AppError.genericUserMessage(): String = when (this) {
        AppError.Network -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        AppError.NotFound -> "No encontramos esos datos."
        AppError.RateLimited -> "Demasiados intentos. Esperá un momento."
        is AppError.Server -> "No pudimos completar la operación. Intentá de nuevo."
        is AppError.Unknown -> "Ocurrió un error inesperado. Intentá de nuevo."
    }

    private companion object {
        /** Firebase's own minimum. Matching it here means the local rule never fights the server. */
        const val MIN_PASSWORD_LENGTH = 6
    }
}
