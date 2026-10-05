package com.racion.diariomercado.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.domain.model.DailySummary
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.repository.DiaryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The "Inicio" screen's ViewModel: observes the daily summary and provides local search filtering.
 *
 * ## State design
 * - [InicioUiState.Loading]: initial state, first emission hasn't arrived
 * - [InicioUiState.Content]: daily summary with entries, consumed nutrition, goal, etc.
 * - [InicioUiState.Error]: read error (should rarely happen since repo catches internally)
 *
 * ## Search filtering
 * The search query is held in ViewModel state and applied locally to the entries from the
 * DailySummary. This way filtering is instant and works offline, using only local Room data.
 *
 * ## Date handling
 * The date is fixed at ViewModel creation (today). If the user leaves the app open past
 * midnight, the date won't update — that's a separate feature (date picker / auto-refresh).
 */
class InicioViewModel(
    private val diaryRepository: DiaryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<InicioUiState>(InicioUiState.Loading)
    val uiState: StateFlow<InicioUiState> = _uiState.asStateFlow()

    /** Current search query for filtering entries locally. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Filtered entries based on search query. */
    val filteredEntries: StateFlow<List<DiaryEntry>> = combine<List<DiaryEntry>, String, List<DiaryEntry>>(
        uiState.map { state ->
            when (state) {
                is InicioUiState.Content -> state.summary.entries
                else -> emptyList<DiaryEntry>()
            }
        }.distinctUntilChanged(),
        _searchQuery
    ) { entries, query ->
        if (query.isBlank()) entries
        else entries.filter { entry ->
            entry.product.name.contains(query, ignoreCase = true) ||
            entry.product.brand?.contains(query, ignoreCase = true) == true ||
            entry.mealSlot.label.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val today = LocalDate.now()

    init {
        observeToday()
    }

    private fun observeToday() {
        viewModelScope.launch {
            diaryRepository.observeDay(LocalDate.now()).collect { summary ->
                _uiState.value = InicioUiState.Content(summary)
            }
        }
    }

    /** Updates the search query for local filtering. */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query.trim()
    }

    /** Clears the search query. */
    fun onClearSearch() {
        _searchQuery.value = ""
    }

    /** Triggers a sync of pending entries to Firestore. */
    fun triggerSync() {
        // Sync is handled by the DiarySyncManager called from AppNavigation after writes
    }
}

/**
 * UI state for the "Inicio" screen.
 */
sealed interface InicioUiState {
    data object Loading : InicioUiState
    data class Content(val summary: DailySummary) : InicioUiState
    data class Error(val message: String) : InicioUiState
}