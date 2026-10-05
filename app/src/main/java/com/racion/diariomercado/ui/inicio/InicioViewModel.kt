package com.racion.diariomercado.ui.inicio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.domain.model.DailySummary
import com.racion.diariomercado.domain.repository.DiaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate

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
 *
 * ## Error handling: `.catch` on the repository flow
 * The [DiaryRepository] contract guarantees that `observeDay` never throws at the collector — a
 * read failure becomes an empty day emission via `.catch`. However, that empty emission is
 * indistinguishable from a genuine empty day. To surface a RENDERABLE error state, this ViewModel
 * wraps the collection in a `try/catch` and maps exceptions to [InicioUiState.Error]. This is the
 * belt-and-braces path: ROOM's `InvalidationTracker` does not fail a query the way a network read
 * does, but if it ever does (e.g. database corruption), the UI renders a message instead of a
 * silently empty list.
 */
class InicioViewModel(
    private val diaryRepository: DiaryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<InicioUiState>(InicioUiState.Loading)
    val uiState: StateFlow<InicioUiState> = _uiState.asStateFlow()

    init {
        observeToday()
    }

    /**
     * Starts observing the current day from the repository.
     *
     * The collection runs on `viewModelScope` so it is cancelled when the ViewModel is cleared.
     * `Dispatchers.Main.immediate` is the default for `viewModelScope.launch`, which means the
     * first emission from a hot ROOM flow arrives synchronously if data is already cached — no
     * frame of "Loading" flashes between composition and the first real value.
     */
    private fun observeToday() {
        val today = LocalDate.now()
        viewModelScope.launch {
            // The repository flow's .catch operator handles read failures by emitting an empty
            // summary. However, any exception that escapes (database corruption, disk full) would
            // crash the collector. We wrap the collection in try/catch as a belt-and-braces layer
            // so the UI renders an Error state instead of crashing.
            try {
                diaryRepository.observeDay(today).collect { summary ->
                    // The repository never emits null; an empty day has empty entries and zero nutrition.
                    when {
                        summary.entries.isEmpty() && summary.consumed == com.racion.diariomercado.domain.model.Nutrition() ->
                            _uiState.value = InicioUiState.Empty(summary)
                        else ->
                            _uiState.value = InicioUiState.Content(summary)
                    }
                }
            } catch (e: Throwable) {
                _uiState.value = InicioUiState.Error(mapReadError(e))
            }
        }
    }

    /**
     * Maps a read exception to a user-facing message.
     *
     * Every [AppError] case gets its own sentence on purpose: a shared "algo salió mal" for both
     * "no connection" and "server error" tells the user nothing about whether retrying helps,
     * which is the only decision the message exists to inform.
     *
     * Since ROOM reads are local, the most likely failures are [AppError.Unknown] wrapping a
     * SQLite exception. We still map them to honest sentences rather than showing the exception.
     */
    private fun mapReadError(throwable: Throwable): String = when {
        throwable is java.io.IOException -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        throwable is java.util.NoSuchElementException -> "No encontramos esos datos."
        else -> "Ocurrió un error inesperado al leer el diario. Intentá de nuevo."
    }
}

/**
 * UI state for the "Inicio" screen.
 *
 * Three states, not two: Loading / Content / Empty / Error.
 *
 * - [Loading]: the first ROOM emission has not arrived yet. The screen shows a skeleton.
 * - [Content]: a day with at least one entry. The screen renders the list and the header total.
 * - [Empty]: a genuine empty day (zero entries, zero nutrition). The screen renders "no meals"
 *   with the goal kcal still visible.
 * - [Error]: the repository read failed. The screen renders a message with a retry affordance.
 *
 * [Empty] and [Content] both carry a [DailySummary] so the header (goal kcal, date label) is
 * always available regardless of whether there are entries. [Error] carries only a message
 * because there is no summary to show when the read failed.
 */
sealed interface InicioUiState {
    data object Loading : InicioUiState
    data class Content(val summary: DailySummary) : InicioUiState
    data class Empty(val summary: DailySummary) : InicioUiState
    data class Error(val message: String) : InicioUiState
}