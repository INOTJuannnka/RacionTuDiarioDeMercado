package com.racion.diariomercado.ui.inicio

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.DailySummary
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.MealSlot
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.repository.DiaryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The "Inicio" screen's ViewModel: observes the current day from the diary repository.
 *
 * ## Why this ViewModel exists
 * The old `AppNavigation` built the diary list by concatenating `PreviewData.meals` with
 * `navResult.confirmedEntries` — a plain `mutableStateOf` that is lost on every config change and
 * does not survive process death. The real diary is served by [DiaryRepository.observeDay], which
 * reads from ROOM and re-emits on every local write. This ViewModel is the seam that makes the
 * screen observe that flow instead of the navigation's in-memory list.
 *
 * ## State design: three states, not two
 * [InicioUiState.Loading] is distinct from [InicioUiState.Empty] because the UI must show a
 * skeleton while the first ROOM emission arrives, not an empty list that flashes "no meals" before
 * the data lands. [InicioUiState.Error] is distinct from empty because a read failure is a fact
 * the user can act on (retry, check storage), whereas "no meals today" is a valid state.
 *
 * ## Why the date is `LocalDate.now()` and not a parameter
 * The "Inicio" screen is always "today". A date parameter would only be used by tests, and the
 * fake repository below can return whatever summary it wants regardless of the date it receives.
 * Hardcoding the date here keeps the production code honest about its single responsibility.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InicioViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -- Loading state --------------------------------------------------------------------------

    @Test
    fun initialStateIsLoading() = runTest(dispatcher) {
        val repository = FakeDiaryRepository()
        val viewModel = InicioViewModel(repository)

        // Before the first emission from the repository flow, the UI must render a skeleton.
        assertEquals(InicioUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun emitsLoadingUntilFirstRepositoryEmission() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().delayFirstEmission()
        val viewModel = InicioViewModel(repository)

        assertEquals(InicioUiState.Loading, viewModel.uiState.value)

        // Release the held emission — now the flow produces a value.
        repository.releaseFirstEmission()
        advanceUntilIdle()

        // An empty day renders as Empty, not Content.
        assertEquals(InicioUiState.Empty(emptySummary()), viewModel.uiState.value)
    }

    // -- Content state --------------------------------------------------------------------------

    @Test
    fun emptyDayRendersAsEmptyNotError() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().answering(emptySummary())
        val viewModel = InicioViewModel(repository)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is InicioUiState.Empty)
        assertTrue((state as InicioUiState.Empty).summary.entries.isEmpty())
        assertFalse(state is InicioUiState.Error)
    }

    @Test
    fun dayWithEntriesRendersContentWithEntriesAndConsumed() = runTest(dispatcher) {
        val summary = summaryWithEntries()
        val repository = FakeDiaryRepository().answering(summary)
        val viewModel = InicioViewModel(repository)

        advanceUntilIdle()

        val state = viewModel.uiState.value as InicioUiState.Content
        assertEquals(3, state.summary.entries.size)
        assertEquals(summary.consumed, state.summary.consumed)
        assertEquals(summary.goalKcal, state.summary.goalKcal)
        assertEquals(summary.dateLabel, state.summary.dateLabel)
    }

    @Test
    fun reEmitsWhenRepositoryUpdates() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().answering(emptySummary())
        val viewModel = InicioViewModel(repository)

        advanceUntilIdle()
        assertEquals(InicioUiState.Empty(emptySummary()), viewModel.uiState.value)

        // Repository emits a new summary (simulating a local write that ROOM picked up).
        repository.emit(summaryWithEntries())
        advanceUntilIdle()

        val state = viewModel.uiState.value as InicioUiState.Content
        assertEquals(3, state.summary.entries.size)
    }

    // -- Error state ----------------------------------------------------------------------------

    @Test
    fun repositoryReadFailureRendersErrorStateNotEmpty() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().failingWith(AppError.Network)
        val viewModel = InicioViewModel(repository)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is InicioUiState.Error)
        assertNotNull((state as InicioUiState.Error).message)
        // The message must be actionable, not generic.
        assertTrue(state.message.contains("conexión"))
        assertFalse(state is InicioUiState.Content)
        assertFalse(state is InicioUiState.Empty)
    }

    @Test
    fun errorStateIsDistinctFromEmptyState() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().failingWith(AppError.Unknown(null))
        val viewModel = InicioViewModel(repository)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is InicioUiState.Error)
        // Empty would mean "no meals today", which is a lie when the read failed.
        assertFalse(state is InicioUiState.Empty)
    }

    @Test
    fun errorMessageIsUserFacingNotTechnical() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().failingWith(AppError.Unknown(Exception("sqlite disk I/O error")))
        val viewModel = InicioViewModel(repository)

        advanceUntilIdle()

        val message = (viewModel.uiState.value as InicioUiState.Error).message
        // No stack traces, no exception class names — just what the user can do.
        assertFalse(message.contains("sqlite"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("I/O"))
    }

    // -- State transitions ----------------------------------------------------------------------

    @Test
    fun loadingToEmptyTransition() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().delayFirstEmission()
        val viewModel = InicioViewModel(repository)

        assertEquals(InicioUiState.Loading, viewModel.uiState.value)

        repository.releaseFirstEmission()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is InicioUiState.Empty)
    }

    @Test
    fun loadingToContentTransition() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().delayFirstEmission().answering(summaryWithEntries())
        val viewModel = InicioViewModel(repository)

        assertEquals(InicioUiState.Loading, viewModel.uiState.value)

        repository.releaseFirstEmission()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is InicioUiState.Content)
    }

    @Test
    fun loadingToErrorTransition() = runTest(dispatcher) {
        val repository = FakeDiaryRepository().delayFirstEmission().failingWith(AppError.Network)
        val viewModel = InicioViewModel(repository)

        assertEquals(InicioUiState.Loading, viewModel.uiState.value)

        repository.releaseFirstEmission()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is InicioUiState.Error)
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun emptySummary(): DailySummary = DailySummary(
        dateLabel = "Hoy",
        consumed = Nutrition(),
        goalKcal = NutritionGoals().kcalPerDay,
        entries = emptyList()
    )

    private fun summaryWithEntries(): DailySummary {
        val arepa = FoodProduct(
            barcode = "LOCAL-AREPA",
            name = "Arepa",
            brand = "Test",
            quantityLabel = "1 und",
            servingGrams = 100.0,
            nutritionPer100g = Nutrition(kcal = 200, carbsG = 30.0, proteinG = 5.0, fatG = 8.0)
        )
        val entries = listOf(
            DiaryEntry(
                id = "1",
                product = arepa,
                servings = 1,
                mealSlot = MealSlot.DESAYUNO,
                loggedAtEpochMillis = System.currentTimeMillis(),
                totalNutrition = Nutrition(kcal = 200, carbsG = 30.0, proteinG = 5.0, fatG = 8.0)
            ),
            DiaryEntry(
                id = "2",
                product = arepa,
                servings = 1,
                mealSlot = MealSlot.ALMUERZO,
                loggedAtEpochMillis = System.currentTimeMillis(),
                totalNutrition = Nutrition(kcal = 200, carbsG = 30.0, proteinG = 5.0, fatG = 8.0)
            ),
            DiaryEntry(
                id = "3",
                product = arepa,
                servings = 1,
                mealSlot = MealSlot.SNACK,
                loggedAtEpochMillis = System.currentTimeMillis(),
                totalNutrition = Nutrition(kcal = 200, carbsG = 30.0, proteinG = 5.0, fatG = 8.0)
            )
        )
        return DailySummary(
            dateLabel = "Hoy",
            consumed = Nutrition(kcal = 600, carbsG = 90.0, proteinG = 15.0, fatG = 24.0),
            goalKcal = NutritionGoals().kcalPerDay,
            entries = entries
        )
    }

    /**
     * In-memory [DiaryRepository] with controllable emissions for testing the UI state machine.
     *
     * The fake uses a [MutableStateFlow] so the test can drive emissions at exact points,
     * making "loading → content" and "loading → error" transitions assertable without sleeps.
     */
    private class FakeDiaryRepository : DiaryRepository {

        private val _flow = MutableStateFlow<DailySummary>(DailySummary(
            dateLabel = "Hoy",
            consumed = Nutrition(),
            goalKcal = NutritionGoals().kcalPerDay,
            entries = emptyList()
        ))
        private var firstEmissionHeld = false
        private var failNext = false
        private var failError: AppError = AppError.Network

        override fun observeDay(date: LocalDate): kotlinx.coroutines.flow.Flow<DailySummary> {
            return if (failNext) {
                kotlinx.coroutines.flow.flow {
                    throw failError.toException()
                }
            } else {
                _flow.asStateFlow()
            }
        }

        override fun observeWeek(weekStart: LocalDate): kotlinx.coroutines.flow.Flow<com.racion.diariomercado.domain.model.WeeklyReport> =
            kotlinx.coroutines.flow.flowOf(com.racion.diariomercado.domain.model.WeeklyReport(
                weekRangeLabel = "Semana",
                averageKcalPerDay = 0,
                bestDayLabel = null,
                activeStreakDays = 0,
                macroSplit = com.racion.diariomercado.domain.model.MacroSplit(0, 0, 0),
                days = emptyList(),
                goalKcal = NutritionGoals().kcalPerDay
            ))

        override suspend fun addEntry(entry: DiaryEntry): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun deleteEntry(entryId: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun recentScans(limit: Int): AppResult<List<DiaryEntry>> = AppResult.Success(emptyList())

        fun delayFirstEmission(): FakeDiaryRepository {
            firstEmissionHeld = true
            return this
        }

        fun releaseFirstEmission(): FakeDiaryRepository {
            firstEmissionHeld = false
            return this
        }

        fun answering(summary: DailySummary): FakeDiaryRepository {
            _flow.value = summary
            return this
        }

        fun failingWith(error: AppError): FakeDiaryRepository {
            failNext = true
            failError = error
            return this
        }

        fun emit(summary: DailySummary) {
            _flow.value = summary
        }

        private fun AppError.toException(): Throwable = when (this) {
            is AppError.Network -> java.io.IOException("network")
            is AppError.NotFound -> java.util.NoSuchElementException()
            is AppError.RateLimited -> java.io.IOException("rate limited")
            is AppError.Server -> java.io.IOException("server: $message")
            is AppError.Unknown -> cause ?: RuntimeException("unknown")
        }
    }
}