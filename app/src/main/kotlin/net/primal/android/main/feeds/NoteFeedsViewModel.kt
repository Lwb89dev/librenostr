package net.primal.android.main.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.primal.android.feeds.list.ui.model.asFeedUi
import net.primal.android.main.feeds.NoteFeedsContract.UiEvent
import net.primal.android.main.feeds.NoteFeedsContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.domain.feeds.defaultNoteFeedsNeedSync
import net.primal.domain.feeds.isLibreNostrHomeFeedSpec
import net.primal.domain.feeds.mergeDefaultNoteFeeds

@HiltViewModel
class NoteFeedsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val feedsRepository: FeedsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        observeFeeds()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.RefreshNoteFeeds -> restoreDefaultNoteFeeds()
                    UiEvent.RestoreDefaultNoteFeeds -> restoreDefaultNoteFeeds()
                    UiEvent.DismissError -> setState { copy(uiError = null) }
                }
            }
        }
    }

    private fun restoreDefaultNoteFeeds() =
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            feedsRepository.persistLocalUserFeeds(
                userId = userId,
                specKind = FeedSpecKind.Notes,
                feeds = defaultLibreNostrNoteFeeds(userId),
            )
            setState { copy(loading = false) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFeeds() =
        viewModelScope.launch {
            activeAccountStore.activeUserId
                .filter { it.isNotBlank() }
                .flatMapLatest { userId ->
                    feedsRepository.observeNotesFeeds(userId = userId)
                        .map { feeds -> userId to feeds }
                }
                .collect { (userId, feeds) -> syncObservedFeeds(userId, feeds) }
        }

    private suspend fun syncObservedFeeds(userId: String, feeds: List<PrimalFeed>) {
        val relevant = feeds.filter { it.spec.isLibreNostrHomeFeedSpec() }
        val merged = mergeDefaultNoteFeeds(userId, relevant)
        setState {
            copy(
                feeds = merged.filter { it.enabled }.map { it.asFeedUi() },
                loading = false,
            )
        }
        if (defaultNoteFeedsNeedSync(relevant, merged)) {
            feedsRepository.persistLocalUserFeeds(
                userId = userId,
                specKind = FeedSpecKind.Notes,
                feeds = merged,
            )
        }
    }
}
