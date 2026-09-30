package com.racion.diariomercado.ui.auth

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
    // Sign-up: the two rules that only exist on the register form
    // -------------------------------------------------------------------------------------

    @Test
    fun mismatchedConfirmationIsRejectedAndTheRepositoryIsNotCalled() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
        val viewModel = LoginViewModel(repository)

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
     */
    private class FakeAuthRepository(
        private val signInResult: AppResult<Unit> = AppResult.Success(Unit),
        private val signUpResult: AppResult<Unit> = AppResult.Success(Unit),
        private val signInAnonymouslyResult: AppResult<Unit> = AppResult.Success(Unit),
        private val promoteResult: AppResult<Unit> = AppResult.Success(Unit)
    ) : AuthRepository {

        private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

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

        override val authState: Flow<AuthState> = state

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
