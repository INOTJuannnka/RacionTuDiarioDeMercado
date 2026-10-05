package com.racion.diariomercado.ui.agregar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the "Agregar" (add food) search screen.
 *
 * ## Why [Empty] is its own state and not `Results(emptyList())`
 * They are the same HTTP 200 with the same zero bytes of information, but they are not the same
 * thing to the user: [Empty] means "Open Food Facts answered, it has nothing for this term" and
 * [Results] means "here are products". Rendering them from one branch is what produces a screen
 * that shows a blank list and reads as a bug. [Empty] also carries the [query] that produced it,
 * so the copy can name the term the user actually typed instead of a hardcoded string.
 *
 * ## Why [Error] carries an [AppError] and not a message
 * Same reason as [com.racion.diariomercado.ui.escaner.EscanerUiState.Error]: the taxonomy is the
 * information and the sentence is presentation. Turning the failure into a `String` here would
 * flatten [AppError.RateLimited] into the same copy as [AppError.Network], and those need opposite
 * recoveries — a rate limit is fixed by waiting, a dead connection by fixing the wifi. The mapping
 * lives in `toAgregarUserMessage()`, mirroring `AppError.toScanUserMessage` for the scanner.
 *
 * ## Why [Searching] carries no query, unlike the scanner's `LookingUp(barcode)`
 * The scanner has to name the code being consulted because a read can be abandoned and resumed.
 * This screen shows one spinner for the text field no matter what is in flight, so the field has
 * nothing to render. The authoritative query is tracked privately in [AgregarViewModel.latestQuery]
 * instead, which is also what makes the stale-response guard in [performSearch] possible.
 */
sealed interface AgregarUiState {

    /** No search in flight and nothing to show: the field is short, empty, or untouched. */
    data object Idle : AgregarUiState

    /** A query was accepted and the repository call is in flight. */
    data object Searching : AgregarUiState

    /** The search succeeded and returned at least one product. */
    data class Results(val items: List<FoodProduct>) : AgregarUiState

    /** The search succeeded and Open Food Facts has nothing for [query]. Not an error. */
    data class Empty(val query: String) : AgregarUiState

    /** The search failed: no connectivity, rate limited, or a server error. Retryable. */
    data class Error(val error: AppError) : AgregarUiState
}

/**
 * Drives the "Agregar" search: a typed query in, an [AgregarUiState] out (ODD task T11).
 *
 * ## This class exists because OFF-4 forbids search-as-you-type
 * Open Food Facts allows **10 requests/min for search** (15 for product reads). Ten requests is
 * about three real searches: a flow that fired one request per keystroke would spend the entire
 * budget typing the word "nutella" and come back `HTTP 503`, which the repository maps to
 * [AppError.RateLimited]. Three rules together are what make the flow survivable, and each one is
 * a separate test in `AgregarViewModelTest`:
 *
 * 1. [onQueryChanged] debounces by [DEBOUNCE_MILLIS] and cancels the previous debounce job, so a
 *    burst of keystrokes costs ONE request. 350 ms is long enough that a normal typing burst never
 *    reaches the API and short enough that the result still feels attached to the last keystroke.
 * 2. A query shorter than [MIN_QUERY_LENGTH] never reaches the API at all — see that constant for
 *    why the threshold is 4 and not the 2 a first draft would suggest.
 * 3. [onSearch] lets the user skip the debounce entirely from the keyboard's search action, so the
 *    flow stays usable for someone who types fast and submits on purpose.
 *
 * ## Last query wins
 * [onQueryChanged] and [onSearch] both route through [performSearch], which cancels the in-flight
 * [searchJob] first. `cancel()` is asynchronous — the old coroutine may already be parked inside
 * the repository call — which is exactly why the response is guarded twice: [ensureActive] rethrows
 * if this job was cancelled (the repository's `apiCall` catches `Throwable`, so a cancelled request
 * can come back as `Failure(Unknown)` instead of propagating), and the `latestQuery` comparison
 * refuses a response that is no longer about what the field holds.
 *
 * ## Construction — the repo's pattern, unchanged
 * Plain constructor injection of the INTERFACE, resolved in the navigation graph with
 * `viewModelFactory { initializer { ... } }`, exactly like `EscanerViewModel`, `LoginViewModel` and
 * `ProfileViewModel`. No companion factory here on purpose: adding one only to this file would be
 * the "new pattern" the rest of the graph then has to reconcile. Everything runs in
 * [viewModelScope] — never `GlobalScope` — so a search in flight dies with the screen that asked
 * for it.
 */
class AgregarViewModel(
    private val foodCatalogRepository: FoodCatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AgregarUiState>(AgregarUiState.Idle)
    val uiState: StateFlow<AgregarUiState> = _uiState.asStateFlow()

    /**
     * The pending debounce, cancelled on every keystroke so only the last one survives to fire.
     *
     * Main-thread only: written by [onQueryChanged]/[onSearch] from the UI, which is where the
     * text field lives.
     */
    private var debounceJob: Job? = null

    /** The in-flight [FoodCatalogRepository.search]. At most one; the previous one is cancelled. */
    private var searchJob: Job? = null

    /** What the field currently holds, trimmed. Kept so [onSearch] and [onRetry] have something to run. */
    private var currentQuery: String = ""

    /**
     * The query whose response is authoritative right now.
     *
     * This is the stale-response guard, and it is necessary because [AgregarUiState.Searching]
     * carries no query: without it, a slow response for the previous term could land on top of a
     * newer search that had already published [AgregarUiState.Searching], and the state alone
     * cannot tell the two apart.
     */
    private var latestQuery: String = ""

    /**
     * The user typed. One keystroke, one (possibly cancelled) debounce.
     *
     * The state goes back to [AgregarUiState.Idle] when the query drops below
     * [MIN_QUERY_LENGTH]: results for a term the field no longer contains are worse than no
     * results, because the list would silently answer a different question than the one asked.
     */
    fun onQueryChanged(query: String) {
        val trimmed = query.trim()
        currentQuery = trimmed

        debounceJob?.cancel()

        if (trimmed.length < MIN_QUERY_LENGTH) {
            cancelPendingWork()
            _uiState.value = AgregarUiState.Idle
            return
        }

        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MILLIS)
            performSearch(trimmed)
        }
    }

    /**
     * Explicit submit from the keyboard's search action: search now, without waiting out the
     * debounce.
     *
     * It bypasses the DEBOUNCE, not the [MIN_QUERY_LENGTH] threshold: submitting "a" is still one
     * request out of the ten OFF-4 gives per minute, and an explicit tap does not make a one-letter
     * query more likely to match something in the catalog.
     */
    fun onSearch() {
        debounceJob?.cancel()

        val trimmed = currentQuery.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            cancelPendingWork()
            _uiState.value = AgregarUiState.Idle
            return
        }

        performSearch(trimmed)
    }

    /**
     * Re-runs the failed search behind the "Reintentar" affordance on [AgregarUiState.Error].
     *
     * Retries [latestQuery] — the query that actually failed — and falls back to what the field
     * holds for the case where the failure arrived before any query was recorded. A no-op while
     * one of those is below [MIN_QUERY_LENGTH], because there is nothing honest to re-issue.
     */
    fun onRetry() {
        debounceJob?.cancel()

        val trimmed = latestQuery.ifEmpty { currentQuery }.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            cancelPendingWork()
            _uiState.value = AgregarUiState.Idle
            return
        }

        performSearch(trimmed)
    }

    /**
     * Cancels the debounce and the in-flight search. Used when the query is too short to search.
     *
     * Leaving the search running would let a response for a term the user just deleted land in
     * [AgregarUiState.Results] after the screen already went back to [AgregarUiState.Idle].
     */
    private fun cancelPendingWork() {
        debounceJob?.cancel()
        debounceJob = null
        searchJob?.cancel()
        searchJob = null
    }

    /**
     * Replaces whatever is running with a search for [query].
     *
     * Ordering is deliberate and matches `EscanerViewModel.startLookup`: the searching state and
     * [latestQuery] are published BEFORE the coroutine is launched, so the guard below can never
     * observe the state it is meant to complete.
     */
    private fun performSearch(query: String) {
        // Last query wins.
        searchJob?.cancel()
        latestQuery = query
        _uiState.value = AgregarUiState.Searching

        searchJob = viewModelScope.launch {
            // The repository returns AppResult and does not throw, so there is deliberately no
            // broad `catch` here: it would only convert a broken contract into a plausible
            // message. What CAN escape is cancellation, handled by the two lines below.
            val result = foodCatalogRepository.search(query)

            // `OpenFoodFactsCatalogRepository.apiCall` catches `Throwable`, so a cancelled request
            // can come back as `Failure(Unknown)` rather than propagating. `ensureActive()` rethrows
            // the cancellation in exactly that case and returns normally on the happy path; it
            // suspends nothing, so it costs nothing when the job is alive.
            ensureActive()

            // The stale-result guard: apply a response only while its own query is still the one
            // being searched. Second line of defence behind `ensureActive` — on a single-threaded
            // dispatcher cancellation usually wins first — but it is the one that stays correct if
            // the repository ever answers on another thread.
            if (query != latestQuery) return@launch

            _uiState.value = when (result) {
                is AppResult.Success ->
                    if (result.data.isEmpty()) AgregarUiState.Empty(query) else AgregarUiState.Results(result.data)
                // Every failure keeps its own identity so the screen can say "esperá un momento"
                // for a rate limit instead of "revisá tu internet", which would be the wrong
                // recovery for a perfectly good connection.
                is AppResult.Failure -> AgregarUiState.Error(result.error)
            }
        }
    }

    companion object {

        /**
         * How long the field has to stay quiet before the search is issued.
         *
         * Long enough to swallow a typing burst (one request for the whole word), short enough that
         * the result still reads as a consequence of the last keystroke.
         */
        const val DEBOUNCE_MILLIS: Long = 350L

        /**
         * Shortest query that is allowed to reach Open Food Facts.
         *
         * ## Why 4 and not the 2 a first draft wanted
         *
         * The threshold is not about politeness toward the API, it is about the two facts that make
         * a one-to-three character search worthless here:
         *
         * - **It costs the same single request as a specific term.** OFF-4 allows 10 search
         *   requests per minute, roughly three usable searches. A request is the scarce resource,
         *   not the keystroke, so filtering on length is the only place a budget can be protected
         *   before it is spent.
         * - **A very short term returns near-unrelated products.** Free text hits the legacy v1
         *   `cgi/search.pl` full-text index (see [FoodCatalogRepository.search]); "a" or "ma" match
         *   on the first letters of thousands of unrelated names. The user gets a full list of the
         *   wrong food and the budget is gone anyway.
         *
         * 2 was the original suggestion and it is below the useful floor: "nut" is already a
         * fragment that OFF answers with unrelated products, and the user has typed nothing that
         * distinguishes it from a typo. 4 is the first length where the term is plausibly a word
         * the user means. `AgregarViewModelTest.debounceCancelsPreviousJob` pins this exact
         * boundary: it types "nut" (3) and requires [AgregarUiState.Idle] with no request issued,
         * then types "nutell" (6) and requires exactly one.
         */
        const val MIN_QUERY_LENGTH: Int = 4
    }
}