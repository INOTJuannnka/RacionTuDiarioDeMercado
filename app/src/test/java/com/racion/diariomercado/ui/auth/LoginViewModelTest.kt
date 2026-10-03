package com.racion.diariomercado.ui.auth

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthErrorMarkers
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import com.racion.diariomercado.ui.screens.auth.LoginViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The rule these tests exist to pin down: **an invalid form never reaches the repository.**
 *
 * That is the one behaviour in [LoginViewModel] that is invisible in the UI. A version that
 * validated *after* awaiting the call would still paint the right message — the field would go
 * red and the form would stay put — so nothing about a screenshot or a manual pass would catch
 * it. The cost is real though: a Firebase `signInWithEmailAndPassword` on a four-character
 * password is a network round trip, and Firebase counts those against the per-account rate limit.
 * So the assertions below check the *call count*, not just the resulting message.
 *
 * ## Why [MainDispatcherRule] is load-bearing
 * `viewModelScope` is hard-wired to `Dispatchers.Main.immediate`, and `kotlinx-coroutines-test` does
 * NOT install a Main dispatcher for you — `Dispatchers.setMain` has to be called explicitly or the
 * coroutine is scheduled onto a dispatcher nobody is pumping. The symptom is silent and specific:
 * the assertions run first and report `signInCalls == 0`, which reads like "the validation swallowed
 * the call" when nothing of the sort happened. The launch is still sitting in a queue.
 *
 * [UnconfinedTestDispatcher] is the specific dispatcher to use here rather than a standard one: the
 * repository call under test is a plain `suspend` with no real suspension point, so unconfined
 * dispatch runs it eagerly at the `launch` and the assertions can follow the call directly. A
 * standard dispatcher would also need an explicit `advanceUntilIdle()` before every assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // -------------------------------------------------------------------------------------
    // Validation short-circuits: the repository is never called
    // -------------------------------------------------------------------------------------

    @Test
    fun blankEmailIsRejectedAndTheRepositoryIsNotCalled() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("")
        viewModel.updatePassword("secreto123")
        viewModel.onSignIn()

        assertEquals("Ingresá tu correo", viewModel.uiState.value.errorMessage)
        assertEquals(0, repository.signInCalls)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun emailWithoutAnAtSignIsRejectedAndTheRepositoryIsNotCalled() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia.ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onSignIn()

        assertEquals(
            "Ingresá un correo electrónico válido",
            viewModel.uiState.value.errorMessage
        )
        assertEquals(0, repository.signInCalls)
    }

    @Test
    fun shortPasswordIsRejectedAndTheRepositoryIsNotCalled() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        // 5 characters: one below the minimum, so a >= 6 check passes and a > 6 check fails.
        viewModel.updatePassword("12345")
        viewModel.onSignIn()

        assertEquals(
            "La contraseña debe tener al menos 6 caracteres",
            viewModel.uiState.value.errorMessage
        )
        assertEquals(0, repository.signInCalls)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun aSixCharacterPasswordIsAcceptedSoTheBoundaryIsPinned() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("123456")
        viewModel.onSignIn()

        assertEquals(1, repository.signInCalls)
        assertTrue(viewModel.uiState.value.isLoggedIn)
    }

    // -------------------------------------------------------------------------------------
    // Sign-in
    // -------------------------------------------------------------------------------------

    @Test
    fun validCredentialsCallTheRepositoryAndFlagTheUserAsLoggedIn() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onSignIn()

        assertEquals(1, repository.signInCalls)
        // Compared as a Pair, not wrapped in a list: `lastSignIn` is a `Pair<String, String>?`,
        // and `List.equals(Pair)` is false, so the old `listOf(...)` form reported a mismatch
        // between two values that were actually identical.
        assertEquals("julia@ejemplo.com" to "secreto123", repository.lastSignIn)
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.errorMessage)
        // The spinner has to be gone, otherwise a successful login would leave the form
        // permanently disabled.
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun aRepositoryFailureSurfacesAMessageAndStopsLoading() = runTest {
        val repository = FakeAuthRepository(signInResult = AppResult.Failure(AppError.Network))
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onSignIn()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertEquals(
            "Sin conexión. Revisá tu internet e intentá de nuevo.",
            state.errorMessage
        )
        assertFalse(state.isLoading)
        assertFalse(state.isLoggedIn)
    }

    // -------------------------------------------------------------------------------------
    // Google sign-in
    //
    // These pin down the three states of the system account picker, which is the part of the flow
    // with no equivalent in the email form: the picker is open, the user dismissed it, or the
    // token came back. Only the third one is allowed to reach the repository, and the first two
    // are the reason the button needs its own progress flag instead of reusing [isLoading].
    // -------------------------------------------------------------------------------------

    @Test
    fun requestingGoogleSignInRaisesTheProgressFlagWithoutTouchingTheRepository() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.onGoogleSignInRequested()

        // The flag is what disables the button while the picker is open. It is NOT `isLoading`:
        // that one means "a network call is in flight", and reusing it would make the email form
        // look busy while the user is still looking at an account chooser.
        assertTrue(viewModel.uiState.value.isGoogleInProgress)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(0, repository.googleSignInCalls)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun dismissingTheAccountPickerIsSilentAndTheRepositoryIsNotCalled() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignInCancelled()

        // No error message on purpose. The user did exactly what they meant to do; telling them
        // "something went wrong" for a deliberate dismissal is the most common way a cancel
        // becomes a support ticket.
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertEquals(0, repository.googleSignInCalls)
    }

    @Test
    fun aGoogleIdTokenReachesTheRepositoryAndFlagsTheUserAsLoggedIn() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token-from-google")

        assertEquals(1, repository.googleSignInCalls)
        assertEquals("id-token-from-google", repository.lastGoogleIdToken)
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun aGoogleFailureStopsTheProgressFlagSoTheButtonCanBeRetried() = runTest {
        val repository = FakeAuthRepository(
            googleSignInResult = AppResult.Failure(AppError.Network)
        )
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token-from-google")

        assertEquals(
            "Sin conexión. Revisá tu internet e intentá de nuevo.",
            viewModel.uiState.value.errorMessage
        )
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        assertFalse(viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun aGoogleAccountThatAlreadyExistsAsksBeforeEndingTheGuestSession() = runTest {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            )
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = LoginViewModel(repository, reassigner)

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token-from-google")

        // Not an error. The account exists, so there IS a way in — it just ends the guest session,
        // which is not a thing to do to someone without asking. The old behaviour answered this
        // with "esa cuenta ya está registrada, iniciá sesión con ella", which on an anonymous
        // session is advice the user cannot follow from this screen.
        assertTrue(viewModel.uiState.value.showGoogleMergeConfirmation)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, repository.googleReplaceCalls)
        assertEquals(0, reassigner.calls)
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
    }

    @Test
    fun confirmingTheMergeInLoginRekeysTheLocalRowsToo() = runTest {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            googleReplaceUid = EXISTING_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            )
        )
        // Pre-set the anonymous uid so currentUid is ANON_UID before the merge
        repository.uid = ANON_UID
        repository.state.value = AuthState.Anonymous
        
        val reassigner = FakeSessionDataReassigner()
        val viewModel = LoginViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token-from-google")
        viewModel.onGoogleMergeConfirmed()
        advanceUntilIdle()

        assertEquals(1, repository.googleReplaceCalls)
        assertEquals("id-token-from-google", repository.lastGoogleReplaceIdToken)
        assertEquals(1, reassigner.calls)
        assertEquals(ANON_UID, reassigner.lastPair?.first)
        assertEquals(EXISTING_UID, reassigner.lastPair?.second)
        assertFalse(viewModel.uiState.value.showGoogleMergeConfirmation)
    }

    @Test
    fun dismissingTheMergeInLoginLeavesTheGuestSessionAlone() = runTest {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            )
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = LoginViewModel(repository, reassigner)

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token-from-google")
        viewModel.onGoogleMergeDismissed()

        assertFalse(viewModel.uiState.value.showGoogleMergeConfirmation)
        assertEquals(0, repository.googleReplaceCalls)
        assertEquals(0, reassigner.calls)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun aDisabledGoogleProviderNamesTheConsoleStepInsteadOfBlamingTheUser() = runTest {
        val repository = FakeAuthRepository(
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.PROVIDER_DISABLED)
            )
        )
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token-from-google")

        val message = viewModel.uiState.value.errorMessage
        assertNotNull(message)
        // `orEmpty()` because the assert above is what proves it is non-null: JUnit4's
        // `assertNotNull` does not smart-cast, and a second `!!` here would read as noise.
        assertTrue(message.orEmpty().contains("Google"))
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
    }

    @Test
    fun aDeviceWithoutGoogleOffersTheEmailPathInsteadOfAskingForARetry() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignInProviderUnavailable()

        // "Intentá de nuevo" is actively wrong on this one: there is no Google on the device, so the
        // same tap fails identically forever. The only useful sentence points at the other button
        // that is still on screen.
        val message = viewModel.uiState.value.errorMessage
        assertNotNull(message)
        assertTrue(message.orEmpty().contains("correo"))
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        // Nothing was exchanged, so the repository must not be touched. Asserting this is what keeps
        // a future refactor from routing this branch through `signInWithGoogle` with an empty token.
        assertEquals(0, repository.googleSignInCalls)
    }

    @Test
    fun anUnreadableCredentialIsReportedThroughTheEmptyTokenBranch() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        // The launcher reports "the sheet came back but we could not read a token" by handing over
        // the same blank token a failed launch would. Both paths must land on one message, so the
        // navigation layer only has to know about `onGoogleSignIn` and not about a second method.
        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("")

        assertEquals(
            "No pudimos leer tu cuenta de Google. Intentá de nuevo.",
            viewModel.uiState.value.errorMessage
        )
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        // A blank token is rejected locally, so the provider is never asked.
        assertEquals(0, repository.googleSignInCalls)
    }

    // -------------------------------------------------------------------------------------
    // Sign-up: the two rules that only exist on the register form
    // -------------------------------------------------------------------------------------

    @Test
    fun mismatchedConfirmationIsRejectedAndTheRepositoryIsNotCalled() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.updateConfirmPassword("secreto124")
        viewModel.onSignUp()

        assertEquals("Las contraseñas no coinciden", viewModel.uiState.value.errorMessage)
        assertEquals(0, repository.signUpCalls)
    }

    @Test
    fun anEmptyConfirmationIsRejectedDistinctlyFromAMismatch() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.updateConfirmPassword("")
        viewModel.onSignUp()

        // The order matters: the blank check runs first, so the user is told to confirm rather
        // than told two passwords do not match when there is only one.
        assertEquals("Confirmá la contraseña", viewModel.uiState.value.errorMessage)
        assertEquals(0, repository.signUpCalls)
    }

    @Test
    fun matchingPasswordsReachTheRepositoryAndFlagTheUserAsLoggedIn() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.updateConfirmPassword("secreto123")
        viewModel.onSignUp()

        assertEquals(1, repository.signUpCalls)
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun aSignUpFailureSurfacesAMessageAndStopsLoading() = runTest {
        val repository = FakeAuthRepository(
            signUpResult = AppResult.Failure(AppError.RateLimited)
        )
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.updateConfirmPassword("secreto123")
        viewModel.onSignUp()

        assertEquals(
            "Demasiados intentos. Esperá un momento.",
            viewModel.uiState.value.errorMessage
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // -------------------------------------------------------------------------------------
    // Error dismissal
    // -------------------------------------------------------------------------------------

    @Test
    fun onErrorShownClearsTheMessageWithoutTouchingTheFormFields() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository, FakeSessionDataReassigner())

        viewModel.updateEmail("julia@ejemplo.com")
        viewModel.updatePassword("12345")
        viewModel.onSignIn()
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.onErrorShown()

        assertNull(viewModel.uiState.value.errorMessage)
        // The user must not have to retype the address just to read a corrected message.
        assertEquals("julia@ejemplo.com", viewModel.uiState.value.email)
        assertEquals("12345", viewModel.uiState.value.password)
    }

    /**
     * In-memory [AuthRepository] with a call counter.
     *
     * [signInCalls] / [signUpCalls] exist only to assert the negative case — that an invalid form
     * did NOT reach the data layer — so they are the whole reason this is a fake rather than a
     * mock with no verification on it.
     *
     * [signInAnonymouslyCalls] / [promoteCalls] follow the same convention for the anonymous-first
     * commands: they are here so a ViewModel that claims an account can assert it reached the
     * repository exactly once, with the arguments the user typed. No test exercises them yet —
     * [LoginViewModel] does not call either — but the contract is three-state now, and a fake that
     * silently dropped them would not compile.
     *
     * [googleSignInCalls] / [lastGoogleIdToken] exist for the same reason on the Google path, and
     * [signInAnonymouslyCalls] above doubles as the signal for whether the repository was asked
     * to link or to sign in — the data-preserving branch is asserted in
     * `FirebaseAuthRepository`'s own tests, not here, because this fake has no Firebase user.
     */
    private class FakeAuthRepository(
        private val signInResult: AppResult<Unit> = AppResult.Success(Unit),
        private val signUpResult: AppResult<Unit> = AppResult.Success(Unit),
        private val signInAnonymouslyResult: AppResult<Unit> = AppResult.Success(Unit),
        private val promoteResult: AppResult<Unit> = AppResult.Success(Unit),
        private val googleSignInResult: AppResult<Unit> = AppResult.Success(Unit),
        private val googleReplaceResult: AppResult<Unit> = AppResult.Success(Unit),
        private val anonymousUid: String? = null,
        private val googleReplaceUid: String? = null
    ) : AuthRepository {

        var state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
            internal set

        var signInCalls = 0
            private set
        var signUpCalls = 0
            private set
        var lastSignIn: Pair<String, String>? = null
            private set
        var signInAnonymouslyCalls = 0
            private set
        var promoteCalls = 0
            private set
        var lastPromotion: Pair<String, String>? = null
            private set
        var googleSignInCalls = 0
            private set
        var lastGoogleIdToken: String? = null
            private set
        /** Counts the DESTRUCTIVE switch, which must never happen without an explicit yes. */
        var googleReplaceCalls = 0
            private set
        var lastGoogleReplaceIdToken: String? = null
            private set
        var uid: String? = null
            internal set

        override val authState: Flow<AuthState> = state

        // Real, and it MOVES. It is read twice around a session switch — before, to know which rows
        // to re-key, and after, to know where they went — so a fake that always answered `null`
        // would let a ViewModel that re-keys nothing pass every test here.
        override val currentUid: String? get() = uid

        override suspend fun signInAnonymously(): AppResult<Unit> {
            signInAnonymouslyCalls++
            if (signInAnonymouslyResult is AppResult.Success) {
                // NOT Authenticated: an anonymous session has a real uid and Firestore access, but
                // no permanent credential. Collapsing the two here would hide the exact distinction
                // the three-state AuthState exists to express.
                state.value = AuthState.Anonymous
            }
            return signInAnonymouslyResult
        }

        override suspend fun promoteToEmailAccount(
            email: String,
            password: String
        ): AppResult<Unit> {
            promoteCalls++
            lastPromotion = email to password
            if (promoteResult is AppResult.Success) {
                state.value = AuthState.Authenticated
            }
            return promoteResult
        }

        override suspend fun signIn(email: String, password: String): AppResult<Unit> {
            signInCalls++
            lastSignIn = email to password
            if (signInResult is AppResult.Success) {
                state.value = AuthState.Authenticated
            }
            return signInResult
        }

        override suspend fun signUp(email: String, password: String): AppResult<Unit> {
            signUpCalls++
            if (signUpResult is AppResult.Success) {
                state.value = AuthState.Authenticated
            }
            return signUpResult
        }

        override suspend fun signOut(): AppResult<Unit> {
            state.value = AuthState.Unauthenticated
            return AppResult.Success(Unit)
        }

        /**
         * A Google credential is a permanent credential, so a successful exchange lands on
         * [AuthState.Authenticated] — same terminal state as email/password, and it is
         * [AuthRepository.authState] that reports it, never this return value.
         */
        override suspend fun signInWithGoogle(idToken: String): AppResult<Unit> {
            googleSignInCalls++
            lastGoogleIdToken = idToken
            // The uid is NOT touched: this is the link path, and linking keeps the same uid. The
            // fake would be lying about the one fact the re-key decision reads.
            if (googleSignInResult is AppResult.Success) {
                state.value = AuthState.Authenticated
            }
            return googleSignInResult
        }

        /**
         * The destructive switch, and the only thing that MOVES [currentUid] in this fake.
         *
         * Modelled as a distinct call from [signInWithGoogle] on purpose. In the real repository the
         * difference is invisible to the caller — both return `Success` — and that invisibility is
         * precisely what made the original defect possible, so the fake refuses to hide it.
         */
        override suspend fun signInWithGoogleReplacingSession(idToken: String): AppResult<Unit> {
            googleReplaceCalls++
            lastGoogleReplaceIdToken = idToken
            if (googleReplaceResult is AppResult.Success) {
                uid = googleReplaceUid
                state.value = AuthState.Authenticated
            }
            return googleReplaceResult
        }
    }

    /**
     * Counts calls and remembers the last pair, which is all the ViewModel needs to be checked.
     *
     * A real [RoomSessionDataReassigner] is deliberately not used here: these tests are about the
     * ORDER of the read-before / write-after pair, and ROOM's own behaviour is already pinned by
     * `SessionDataReassignerTest` against a real database.
     */
    private class FakeSessionDataReassigner : SessionDataReassigner {
        var calls = 0
            private set
        var lastPair: Pair<String, String>? = null
            private set

        override suspend fun reassign(fromUserId: String, toUserId: String): AppResult<Unit> {
            calls++
            lastPair = fromUserId to toUserId
            return AppResult.Success(Unit)
        }
    }

    private companion object {
        const val ANON_UID = "anon-uid-1"
        const val EXISTING_UID = "existing-uid-2"
    }
}

/**
 * Installs a test dispatcher as `Dispatchers.Main` for the duration of one test.
 *
 * `resetMain` in [finished] is not optional bookkeeping: without it the override leaks into the
 * next test method in the same JVM, and the failure mode is order-dependent flakiness rather than a
 * clean error, which is the worst kind to debug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
