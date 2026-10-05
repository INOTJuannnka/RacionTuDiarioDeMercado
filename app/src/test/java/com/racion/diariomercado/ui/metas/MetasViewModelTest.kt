package com.racion.diariomercado.ui.metas

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MacroSplit
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.domain.repository.GoalsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The "Metas" screen's ViewModel: observes and saves nutrition goals.
 *
 * ## Why this ViewModel exists
 * The old `MetasScreen` was a pure composable that took `goals: NutritionGoals = PreviewData.goals`
 * and an `onSave` callback. The goals came from the navigation graph's static preview data and
 * writes were fire-and-forget coroutines launched from the graph's `rememberCoroutineScope()`,
 * with no error reporting. This ViewModel makes the screen observe [GoalsRepository.observeGoals]
 * (which reads from Firestore and emits defaults when unset) and save through
 * [GoalsRepository.saveGoals] with proper error handling.
 *
 * ## State design: the goals object IS the content
 * Unlike the diary, goals have no "empty" state — [NutritionGoals] has sensible defaults for every
 * field, and [GoalsRepository.observeGoals] always emits a value (defaults on first read). So the
 * UI state is just the goals object plus a loading flag for the initial fetch and an error message
 * for write failures. Read failures from Firestore are surfaced as the defaults emission (per the
 * repository contract), so the UI never shows a "failed to load goals" state — it shows the
 * defaults, which is the honest "you haven't set goals yet" state.
 *
 * ## Write errors are reported, not swallowed
 * The old graph launched `saveGoals` in a fire-and-forget coroutine and navigated away immediately.
 * If the write failed, the user never knew. This ViewModel exposes the write result so the screen
 * can show a message and let the user retry. The navigation decision (whether to go back) stays
 * with the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetasViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -- Initial load ---------------------------------------------------------------------------

    @Test
    fun initialStateIsLoading() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().delayFirstEmission()
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        // Before the first emission, the UI shows a skeleton.
        assertEquals(MetasUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun emitsLoadingUntilFirstRepositoryEmission() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().delayFirstEmission()
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        assertEquals(MetasUiState.Loading, viewModel.uiState.value)

        repository.releaseFirstEmission(defaultGoals())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MetasUiState.Content)
        assertEquals(defaultGoals(), (state as MetasUiState.Content).goals)
    }

    @Test
    fun firstEmissionDefaultsWhenUserNeverOpenedMetas() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().answering(NutritionGoals()) // defaults
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()

        val state = viewModel.uiState.value as MetasUiState.Content
        assertEquals(NutritionGoals(), state.goals)
    }

    // -- Content updates ------------------------------------------------------------------------

    @Test
    fun reEmitsWhenRepositoryUpdates() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().answering(defaultGoals())
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()
        assertEquals(defaultGoals(), (viewModel.uiState.value as MetasUiState.Content).goals)

        val updated = defaultGoals().copy(kcalPerDay = 2200)
        repository.emit(updated)
        advanceUntilIdle()

        assertEquals(2200, (viewModel.uiState.value as MetasUiState.Content).goals.kcalPerDay)
    }

    @Test
    fun localEditsDoNotAffectRepositoryUntilSave() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().answering(defaultGoals())
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()

        // User edits the weight slider — this is local UI state only.
        viewModel.onWeightChanged(70f)
        advanceUntilIdle()

        // Repository has not been called.
        assertEquals(0, repository.saveCalls)
        // The ViewModel's exposed goals reflect the local edit.
        assertEquals(70f, (viewModel.uiState.value as MetasUiState.Content).goals.targetWeightKg)
    }

    // -- Save -----------------------------------------------------------------------------------

    @Test
    fun saveCallsRepositoryWithCurrentGoals() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().answering(defaultGoals())
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()

        viewModel.onWeightChanged(70f)
        viewModel.onKcalChanged(2100)
        viewModel.onActiveDaysChanged(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        viewModel.onSave(viewModel.uiState.value as MetasUiState.Content).goals

        advanceUntilIdle()

        assertEquals(1, repository.saveCalls)
        val saved = repository.lastSavedGoals!!
        assertEquals(70f, saved.targetWeightKg)
        assertEquals(2100, saved.kcalPerDay)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), saved.activeDays)
    }

    @Test
    fun saveFailureSurfacesErrorMessage() = runTest(dispatcher) {
        val repository = FakeGoalsRepository()
            .answering(defaultGoals())
            .failingSaveWith(AppError.Network)
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()

        viewModel.onSave((viewModel.uiState.value as MetasUiState.Content).goals)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MetasUiState.Content)
        val content = state as MetasUiState.Content
        assertNotNull(content.errorMessage)
        assertTrue(content.errorMessage!!.contains("conexión"))
    }

    @Test
    fun saveSuccessClearsAnyPreviousError() = runTest(dispatcher) {
        val repository = FakeGoalsRepository()
            .answering(defaultGoals())
            .failingSaveWith(AppError.Network)
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()
        viewModel.onSave((viewModel.uiState.value as MetasUiState.Content).goals)
        advanceUntilIdle()
        assertNotNull((viewModel.uiState.value as MetasUiState.Content).errorMessage)

        // Now the save succeeds.
        repository.failingSaveWith(null) // clear failure
        viewModel.onSave((viewModel.uiState.value as MetasUiState.Content).goals)
        advanceUntilIdle()

        assertNull((viewModel.uiState.value as MetasUiState.Content).errorMessage)
    }

    @Test
    fun saveDoesNotModifyGoalsOnFailure() = runTest(dispatcher) {
        val repository = FakeGoalsRepository()
            .answering(defaultGoals())
            .failingSaveWith(AppError.Network)
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()
        viewModel.onWeightChanged(75f)
        viewModel.onSave((viewModel.uiState.value as MetasUiState.Content).goals)
        advanceUntilIdle()

        // The goals in the UI state still reflect the local edit (75f),
        // but the repository was called with 75f and failed.
        // A retry should still send 75f.
        assertEquals(75f, (viewModel.uiState.value as MetasUiState.Content).goals.targetWeightKg)
    }

    @Test
    fun multipleSavesEachCallRepository() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().answering(defaultGoals())
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()

        viewModel.onWeightChanged(70f)
        viewModel.onSave((viewModel.uiState.value as MetasUiState.Content).goals)
        advanceUntilIdle()

        viewModel.onWeightChanged(72f)
        viewModel.onSave((viewModel.uiState.value as MetasUiState.Content).goals)
        advanceUntilIdle()

        assertEquals(2, repository.saveCalls)
    }

    // -- Macro split ----------------------------------------------------------------------------

    @Test
    fun macroSplitChangesAreTrackedLocally() = runTest(dispatcher) {
        val repository = FakeGoalsRepository().answering(defaultGoals())
        val authRepository = FakeAuthRepository().apply { setState(AuthState.Authenticated) }
        val viewModel = MetasViewModel(repository, authRepository)

        advanceUntilIdle()

        viewModel.onMacroSplitChanged(MacroSplit(50, 25, 25))
        advanceUntilIdle()

        val state = viewModel.uiState.value as MetasUiState.Content
        assertEquals(50, state.goals.macroSplit.carbsPct)
        assertEquals(25, state.goals.macroSplit.proteinPct)
        assertEquals(25, state.goals.macroSplit.fatPct)
        assertEquals(0, repository.saveCalls)
    }

    // -- Helpers --------------------------------------------------------------------------------

    // -- Helpers --------------------------------------------------------------------------------

    private fun defaultGoals(): NutritionGoals = NutritionGoals(
        targetWeightKg = 65f,
        currentWeightKg = 70f,
        kcalPerDay = 2000,
        activeDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        macroSplit = MacroSplit(45, 25, 30)
    )

    /**
     * In-memory [GoalsRepository] with controllable emissions for testing.
     */
    private class FakeGoalsRepository : GoalsRepository {

        private val _flow = MutableStateFlow<NutritionGoals>(NutritionGoals())
        var saveCalls = 0
            private set
        var lastSavedGoals: NutritionGoals? = null
            private set
        private var failSave = false
        private var failError: AppError = AppError.Network
        private var firstEmissionHeld = false

        override fun observeGoals(): kotlinx.coroutines.flow.Flow<NutritionGoals> = _flow.asStateFlow()

        override suspend fun saveGoals(goals: NutritionGoals): AppResult<Unit> {
            saveCalls++
            lastSavedGoals = goals
            return if (failSave) AppResult.Failure(failError) else AppResult.Success(Unit)
        }

        fun delayFirstEmission(): FakeGoalsRepository {
            firstEmissionHeld = true
            return this
        }

        fun releaseFirstEmission(goals: NutritionGoals = NutritionGoals()): FakeGoalsRepository {
            firstEmissionHeld = false
            _flow.value = goals
            return this
        }

        fun answering(goals: NutritionGoals): FakeGoalsRepository {
            if (!firstEmissionHeld) _flow.value = goals
            return this
        }

        fun failingSaveWith(error: AppError?): FakeGoalsRepository {
            failSave = error != null
            if (error != null) failError = error!!
            return this
        }

        fun emit(goals: NutritionGoals) {
            _flow.value = goals
        }
    }

    /**
     * Fake [AuthRepository] with controllable auth state for testing.
     */
    private class FakeAuthRepository : AuthRepository {
        private val _state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        override val authState: kotlinx.coroutines.flow.Flow<AuthState> = _state.asStateFlow()
        override val currentUid: String?
            get() {
                val s = _state.value
                return when (s) {
                    is AuthState.Anonymous -> "test-anon-uid"
                    is AuthState.Authenticated -> "test-auth-uid"
                    else -> null
                }
            }

        fun setState(state: AuthState) {
        _state.value = state
    }

        override suspend fun signInWithGoogleReplacingSession(idToken: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun signInAnonymously(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun promoteToEmailAccount(email: String, password: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun signIn(email: String, password: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun signInWithGoogle(idToken: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun signUp(email: String, password: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun signOut(): AppResult<Unit> = AppResult.Success(Unit)
    }
}