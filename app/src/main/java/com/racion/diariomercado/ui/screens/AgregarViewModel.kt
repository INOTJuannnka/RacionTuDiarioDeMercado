package com.racion.diariomercado.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Agregar" screen's ViewModel: searches and selects foods from the catalog.
 *
 * ## State design
 * - [AgregarUiState.Idle]: initial state, no query entered yet
 * - [AgregarUiState.Searching]: a search request is in flight
 * - [AgregarUiState.Results]: search completed (may be empty list)
 * - [AgregarUiState.Error]: search failed with a user-facing message
 *
 * ## Search behavior (OFF-4)
 * Open Food Facts limits search to 10 requests/minute. This ViewModel enforces that by:
 * - Requiring an explicit submit (user taps "Buscar" or presses enter), no search-as-you-type
 * - Debouncing is handled by the UI (the search field only submits on IME action or button tap)
 *
 * The results are held in state until the next search or the screen is left.
 */
class AgregarViewModel(
    private val catalogRepository: FoodCatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AgregarUiState>(AgregarUiState.Idle)
    val uiState: StateFlow<AgregarUiState> = _uiState.asStateFlow()

    /**
     * Triggers a catalog search for the given query.
     *
     * Called when the user submits the search field (IME action or button).
     * Updates state through Searching -> Results/Error.
     */
    fun onSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = AgregarUiState.Idle
            return
        }

        _uiState.value = AgregarUiState.Searching
        viewModelScope.launch {
            val result = catalogRepository.search(trimmed, page = 1, pageSize = 20)
            _uiState.value = when (result) {
                is AppResult.Success -> AgregarUiState.Results(result.data)
                is AppResult.Failure -> AgregarUiState.Error(mapError(result.error))
            }
        }
    }

    /** Clears results back to idle (e.g. when user clears the query). */
    fun onClearQuery() {
        _uiState.value = AgregarUiState.Idle
    }

    private fun mapError(error: AppError): String = when (error) {
        is AppError.Network -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        is AppError.RateLimited -> "Demasiadas búsquedas. Esperá un momento e intentá de nuevo."
        is AppError.Server -> "Error del servidor (${error.code ?: "?"}). Intentá de nuevo en un rato."
        is AppError.NotFound -> "No se encontraron productos."
        is AppError.Unknown -> "Ocurrió un error inesperado. Intentá de nuevo."
    }
}

/**
 * UI state for the "Agregar" screen.
 */
sealed interface AgregarUiState {
    data object Idle : AgregarUiState
    data object Searching : AgregarUiState
    data class Results(val products: List<FoodProduct>) : AgregarUiState
    data class Error(val message: String) : AgregarUiState
}