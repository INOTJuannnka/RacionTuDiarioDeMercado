package com.racion.diariomercado.ui.metas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MacroSplit
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.domain.repository.GoalsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 *
 * ## Local edits vs. saved state
 * The sliders and day chips in the UI are local edits — they update the exposed [MetasUiState.Content.goals]
 * immediately so the user sees their changes, but they do NOT call the repository until [onSave]
 * is invoked. This matches the screen's current behaviour where the "Guardar metas" button is the
 * explicit commit point.
 */
class MetasViewModel(
    private val goalsRepository: GoalsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MetasUiState>(MetasUiState.Loading)
    val uiState: StateFlow<MetasUiState> = _uiState.asStateFlow()

    /** Tracks the last goals emitted by the repository to detect local edits. */
    private var lastRepositoryGoals: NutritionGoals? = null

    init {
        observeGoals()
    }

    /**
     * Observes goals from the repository, combined with auth state to determine canSave.
     */
    private fun observeGoals() {
        viewModelScope.launch {
            combine(
                goalsRepository.observeGoals(),
                authRepository.authState.distinctUntilChanged()
            ) { goals, authState ->
                val canSave = authState is AuthState.Anonymous || authState is AuthState.Authenticated
                val current = _uiState.value
                val mergedGoals = when (current) {
                    is MetasUiState.Content -> {
                        val hasLocalEdits = lastRepositoryGoals != null &&
                            current.goals != lastRepositoryGoals
                        if (hasLocalEdits) current.goals else goals
                    }
                    else -> goals
                }
                val preservedError = (current as? MetasUiState.Content)?.errorMessage
                lastRepositoryGoals = goals
                MetasUiState.Content(
                    goals = mergedGoals,
                    errorMessage = preservedError,
                    canSave = canSave
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    /** Updates the target weight locally (slider drag). Does not persist. */
    fun onWeightChanged(weightKg: Float) {
        _uiState.update { state ->
            (state as? MetasUiState.Content)?.let { content ->
                content.copy(goals = content.goals.copy(targetWeightKg = weightKg))
            } ?: state
        }
    }

    /** Updates the daily kcal goal locally (slider drag). Does not persist. */
    fun onKcalChanged(kcal: Int) {
        _uiState.update { state ->
            (state as? MetasUiState.Content)?.let { content ->
                content.copy(goals = content.goals.copy(kcalPerDay = kcal))
            } ?: state
        }
    }

    /** Updates the active days locally (chip tap). Does not persist. */
    fun onActiveDaysChanged(activeDays: Set<DayOfWeek>) {
        _uiState.update { state ->
            (state as? MetasUiState.Content)?.let { content ->
                content.copy(goals = content.goals.copy(activeDays = activeDays))
            } ?: state
        }
    }

    /** Updates the macro split locally. Does not persist. */
    fun onMacroSplitChanged(macroSplit: MacroSplit) {
        _uiState.update { state ->
            (state as? MetasUiState.Content)?.let { content ->
                content.copy(goals = content.goals.copy(macroSplit = macroSplit))
            } ?: state
        }
    }

    /**
     * Persists the given goals through the repository.
     *
     * The save runs on `viewModelScope` so it survives config changes. The UI state is updated
     * with the result: on success the error message is cleared; on failure the error message is
     * set so the screen can render it and offer a retry.
     */
    fun onSave(goals: NutritionGoals) {
        _uiState.update { state ->
            (state as? MetasUiState.Content)?.copy(errorMessage = null) ?: state
        }
        viewModelScope.launch {
            val result = goalsRepository.saveGoals(goals)
            _uiState.update { state ->
                (state as? MetasUiState.Content)?.copy(
                    errorMessage = when (result) {
                        is AppResult.Success -> null
                        is AppResult.Failure -> mapWriteError(result.error)
                    }
                ) ?: state
            }
        }
    }

    /**
     * Maps a write error to a user-facing message.
     *
     * Same sentence-per-error philosophy as the rest of the app: the message must tell the user
     * what to DO, not what went wrong internally.
     */
    private fun mapWriteError(error: AppError): String = when (error) {
        is AppError.Network -> "Sin conexión. Revisá tu internet e intentá de nuevo."
        is AppError.RateLimited -> "Demasiados intentos. Esperá un momento e intentá de nuevo."
        is AppError.Server -> {
            // If the error message contains the Firestore code, show it for debugging
            val msg = error.message ?: ""
            if (msg.contains("PERMISSION_DENIED") || msg.contains("7")) {
                "Permiso denegado: verificá que el proveedor de Google esté habilitado en Firebase Console (Authentication → Sign-in method) y que las reglas de Firestore coincidan con users/{uid}/goals."
            } else if (msg.contains("No Firebase session") || msg.contains("cannot write")) {
                "No hay sesión activa. Cerrá y volvé a abrir la app, o iniciá sesión de nuevo."
            } else {
                "No pudimos guardar las metas (${error.code ?: "error servidor"}). Intentá de nuevo en un rato."
            }
        }
        is AppError.Unknown -> "Ocurrió un error inesperado al guardar. Intentá de nuevo."
        // NotFound should not happen for a write, but we handle it anyway.
        AppError.NotFound -> "No se encontró el documento de metas. Intentá de nuevo."
    }
}

/**
 * UI state for the "Metas" screen.
 *
 * Two states: Loading / Content.
 *
 * - [Loading]: the first Firestore emission has not arrived yet. The screen shows a skeleton.
 * - [Content]: the goals object (always present, defaults on first read) plus an optional error
 *   message from the last save attempt.
 *
 * There is no [Empty] state because [NutritionGoals] has defaults for every field, and
 * [GoalsRepository.observeGoals] emits those defaults when the document does not exist. The
 * "user never opened Metas" state is indistinguishable from "user set goals to exactly the
 * defaults", which is the correct UX — both render the same sliders at the same positions.
 *
 * There is no [Error] state for reads because the repository contract surfaces read failures as
 * the defaults emission. The UI never shows "failed to load goals" — it shows the defaults, which
 * is the honest "you haven't set goals yet" state. Write errors are carried as an optional
 * message on [Content] so the screen can render them inline without changing state.
 */
sealed interface MetasUiState {
    data object Loading : MetasUiState
    data class Content(
        val goals: NutritionGoals,
        val errorMessage: String? = null,
        /** True when the auth state allows persistence (Anonymous or Authenticated). */
        val canSave: Boolean = false
    ) : MetasUiState
}