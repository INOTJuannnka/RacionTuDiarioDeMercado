package com.racion.diariomercado.ui.agregar

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgregarViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun debounceCancelsPreviousJob() = runTest(dispatcher) {
        val repository = FakeFoodCatalogRepository()
        val viewModel = AgregarViewModel(repository)

        viewModel.onQueryChanged("nut")
        advanceUntilIdle()
        assertEquals(AgregarUiState.Idle, viewModel.uiState.value)

        viewModel.onQueryChanged("nutella")
        advanceTimeBy(200)
        viewModel.onQueryChanged("nutell")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(listOf("nutell"), repository.requestedQueries)
    }

    @Test
    fun singleRequestForSevenCharQuery() = runTest(dispatcher) {
        val repository = FakeFoodCatalogRepository()
        val viewModel = AgregarViewModel(repository)

        viewModel.onQueryChanged("nutella")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, repository.requestedQueries.size)
        assertEquals("nutella", repository.requestedQueries[0])
    }

    @Test
    fun inFlightSearchCancelledByNewOne() = runTest(dispatcher) {
        val repository = FakeFoodCatalogRepository().delay(1000)
        val viewModel = AgregarViewModel(repository)

        viewModel.onQueryChanged("nutella")
        advanceTimeBy(350)
        advanceUntilIdle()

        viewModel.onQueryChanged("choco")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(2, repository.requestedQueries.size)
    }

    private class FakeFoodCatalogRepository : FoodCatalogRepository {
        private val requested = mutableListOf<String>()
        private var delayMs: Long = 0

        val requestedQueries: List<String> get() = requested.toList()

        fun delay(ms: Long): FakeFoodCatalogRepository {
            delayMs = ms
            return this
        }

        override suspend fun productByBarcode(barcode: String): AppResult<FoodProduct> {
            return AppResult.Failure(AppError.NotFound)
        }

        override suspend fun search(query: String, page: Int, pageSize: Int): AppResult<List<FoodProduct>> {
            requested.add(query)
            if (delayMs > 0) delay(delayMs)
            return AppResult.Success(
                listOf(
                    FoodProduct(
                        barcode = "123",
                        name = "Test ",
                        brand = null,
                        quantityLabel = "100 g",
                        servingGrams = 100.0,
                        nutritionPer100g = Nutrition(kcal = 100)
                    )
                )
            )
        }
    }
}
