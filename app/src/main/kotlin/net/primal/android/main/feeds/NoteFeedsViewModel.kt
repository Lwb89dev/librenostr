package net.primal.android.main.feeds

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.primal.android.core.errors.UiError
import net.primal.android.feeds.list.ui.model.asFeedUi
import net.primal.android.main.feeds.NoteFeedsContract.UiEvent
import net.primal.android.main.feeds.NoteFeedsContract.UiState
import net.primal.android.navigation.identifier
import net.primal.android.navigation.npub
import net.primal.android.navigation.primalName
import net.primal.android.navigation.streamNaddr
import net.primal.android.notes.feed.model.asStreamPillUi
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.map
import net.primal.core.utils.onFailure
import net.primal.core.utils.onSuccess
import net.primal.core.utils.runCatching
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.domain.feeds.isLibreNostrHomeFeedSpec
import net.primal.domain.nostr.toNostrString
import net.primal.domain.nostr.utils.npubToPubkey
import net.primal.domain.profile.ProfileRepository
import net.primal.domain.streams.StreamRepository

@HiltViewModel
class NoteFeedsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val activeAccountStore: ActiveAccountStore,
    private val feedsRepository: FeedsRepository,
    private val profileRepository: ProfileRepository,
    private val streamRepository: StreamRepository,
) : ViewModel() {

    private val hostNpub = savedStateHandle.npub
    private val streamIdentifier = savedStateHandle.identifier
    private val hostPrimalName = savedStateHandle.primalName
    private val streamNaddr = savedStateHandle.streamNaddr

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private val _effects = Channel<NoteFeedsContract.SideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: NoteFeedsContract.SideEffect) = viewModelScope.launch { _effects.send(effect) }

    init {
        resolveStreamParams()
        observeLiveEventsFromFollows()
        observeEvents()
        observeFeeds()
    }

    private fun resolveStreamParams() =
        viewModelScope.launch {
            if (streamNaddr != null) {
                setEffect(NoteFeedsContract.SideEffect.StartStream(naddr = streamNaddr))
                return@launch
            }

            if (streamIdentifier == null) return@launch

            val hostPubkey = when {
                hostNpub != null -> hostNpub.npubToPubkey()

                hostPrimalName != null ->
                    runCatching { profileRepository.fetchProfileId(primalName = hostPrimalName) }.getOrNull()

                else -> null
            }

            if (hostPubkey != null) {
                streamRepository.findStreamNaddr(hostPubkey = hostPubkey, identifier = streamIdentifier)
                    .onSuccess { naddr ->
                        setEffect(NoteFeedsContract.SideEffect.StartStream(naddr = naddr.toNostrString()))
                    }
                    .onFailure {
                        Napier.w(throwable = it) { "Failed to find stream naddr for hostPubkey=$hostPubkey." }
                        setState { copy(uiError = UiError.StreamNotFound) }
                    }
            } else {
                setState { copy(uiError = UiError.InvalidNaddr) }
            }
        }

    @OptIn(FlowPreview::class)
    private fun observeLiveEventsFromFollows() =
        viewModelScope.launch {
            activeAccountStore.activeUserAccount
                .map { it.contentDisplaySettings.showLiveStreams }
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    setState { copy(showLiveStreams = enabled, streams = emptyList()) }
                    if (!enabled) {
                        flowOf(emptyList())
                    } else {
                        streamRepository.observeLiveEventsFromFollows(
                            userId = activeAccountStore.activeUserId(),
                        )
                    }
                }
                .collectLatest { streams ->
                    setState { copy(streams = streams.map { it.asStreamPillUi() }) }
                }
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

    private fun observeFeeds() =
        viewModelScope.launch {
            feedsRepository.observeNotesFeeds(userId = activeAccountStore.activeUserId())
                .collect { feeds ->
                    val kept = feeds.filter { it.enabled && it.spec.isLibreNostrHomeFeedSpec() }
                    if (kept.isEmpty()) {
                        restoreDefaultNoteFeeds()
                    } else {
                        setState {
                            copy(
                                feeds = kept.map { it.asFeedUi() },
                                loading = false,
                            )
                        }
                    }
                }
        }
}
