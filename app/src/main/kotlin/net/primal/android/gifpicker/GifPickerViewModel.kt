package net.primal.android.gifpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.primal.android.core.errors.UiError
import net.primal.android.gifpicker.GifPickerContract.SideEffect
import net.primal.android.gifpicker.GifPickerContract.UiEvent
import net.primal.android.gifpicker.GifPickerContract.UiState
import net.primal.android.gifpicker.domain.asGifItem
import net.primal.core.utils.onFailure
import net.primal.core.utils.onSuccess
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.gifs.GifSearchApi
import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifSearchPage

@HiltViewModel
class GifPickerViewModel @Inject constructor(
    private val gifSearchApi: GifSearchApi,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private val _effect = Channel<SideEffect>()
    val effect = _effect.receiveAsFlow()
    private fun setEffect(effect: SideEffect) = viewModelScope.launch { _effect.send(effect) }

    companion object {
        private val SEARCH_DEBOUNCE_DURATION = 0.42.seconds
        private const val MIN_SUGGESTION_QUERY_LENGTH = 2
    }

    /** Where the visible listing continues, or null when it is complete. */
    private var nextCursor: GifCursor? = null

    /** The query the visible listing belongs to; a late page for any other query is dropped. */
    private var listingQuery: String? = null

    private var loadMoreJob: Job? = null
    private var initialLoadJob: Job? = null

    init {
        observeEvents()
        observeDebouncedSearchQuery()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    UiEvent.PickerShown -> onPickerShown()
                    is UiEvent.UpdateSearchQuery -> setState { copy(searchQuery = event.query) }
                    is UiEvent.SelectGif -> setEffect(SideEffect.GifSelected(gif = event.gif))
                    UiEvent.LoadMoreGifs -> loadMoreGifs()
                    UiEvent.DismissError -> setState { copy(error = null) }
                }
            }
        }

    /**
     * Loads what to show before anything is typed — once, or again only if the previous attempt
     * left the picker empty (it failed, or the picker closed before it finished). Reopening a
     * picker that already has results keeps them, and keeps whatever the user was searching.
     */
    private fun onPickerShown() {
        val current = _state.value
        if (current.gifItems.isNotEmpty() || current.searching || current.searchQuery.isNotBlank()) return
        initialLoadJob = viewModelScope.launch { loadFirstPage(query = "") }
    }

    @OptIn(FlowPreview::class)
    private fun observeDebouncedSearchQuery() =
        viewModelScope.launch {
            events.filterIsInstance<UiEvent.UpdateSearchQuery>()
                .debounce(SEARCH_DEBOUNCE_DURATION)
                .collectLatest { event -> onSearchQuerySettled(query = event.query) }
        }

    private suspend fun onSearchQuerySettled(query: String) {
        // A search supersedes the opening page if that is still on its way.
        initialLoadJob?.cancel()
        // Both at once: the chips should not wait for the results, nor the results for the
        // chips. collectLatest cancels both when the text changes again.
        coroutineScope {
            launch { refreshTopics(query = query) }
            loadFirstPage(query = query)
        }
    }

    private suspend fun loadFirstPage(query: String) {
        loadMoreJob?.cancel()
        listingQuery = query
        nextCursor = null
        setState { copy(gifItems = emptyList(), searching = true) }
        runCatching { fetchPage(query = query, cursor = null) }
            .onSuccess { page -> applyPage(query = query, page = page, append = false) }
            .onFailure { error ->
                Napier.w(throwable = error) { "Failed to load GIFs for '$query'" }
                setState { copy(searching = false, error = UiError.GenericError()) }
            }
    }

    private fun loadMoreGifs() {
        val cursor = nextCursor
        val query = listingQuery
        val busy = _state.value.searching || loadMoreJob?.isActive == true
        if (cursor == null || query == null || busy) return

        loadMoreJob = viewModelScope.launch {
            setState { copy(searching = true) }
            runCatching { fetchPage(query = query, cursor = cursor) }
                .onSuccess { page -> applyPage(query = query, page = page, append = true) }
                .onFailure { error ->
                    Napier.w(throwable = error) { "Failed to load more GIFs for '$query'" }
                    setState { copy(searching = false) }
                }
        }
    }

    private suspend fun fetchPage(query: String, cursor: GifCursor?): GifSearchPage =
        if (query.isBlank()) {
            gifSearchApi.trending(cursor = cursor)
        } else {
            gifSearchApi.search(query = query.trim(), cursor = cursor)
        }

    private fun applyPage(
        query: String,
        page: GifSearchPage,
        append: Boolean,
    ) {
        // The user typed something else while this page was on its way: it belongs to a listing
        // that is no longer on screen.
        if (query != listingQuery) return
        nextCursor = page.nextCursor
        val gifs = page.results.map { it.asGifItem() }
        setState {
            copy(
                gifItems = if (append) (gifItems + gifs).distinctBy { it.id } else gifs,
                source = page.source,
                searching = false,
            )
        }
    }

    private suspend fun refreshTopics(query: String) {
        val trimmed = query.trim()
        val topics = when {
            trimmed.isEmpty() -> GifPickerContract.DEFAULT_TOPICS
            trimmed.length < MIN_SUGGESTION_QUERY_LENGTH -> emptyList()
            else -> gifSearchApi.suggest(query = trimmed).filterNot { it.equals(trimmed, ignoreCase = true) }
        }
        setState { copy(topics = topics) }
    }
}
