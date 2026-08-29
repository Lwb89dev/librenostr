package net.primal.android.feeds.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.feeds.list.FeedListContract.UiEvent
import net.primal.android.feeds.list.FeedListContract.UiState
import net.primal.android.feeds.list.FeedListContract.UiState.FeedMarketplaceStage
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.android.feeds.list.ui.model.asFeedUi
import net.primal.android.feeds.list.ui.model.asPrimalFeed
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.feeds.DvmFeed
import net.primal.domain.feeds.FEED_KIND_LIST
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.feeds.buildFollowSetFeedSpec
import net.primal.domain.feeds.buildSpec
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.domain.feeds.defaultNoteFeedsNeedSync
import net.primal.domain.feeds.isLibreNostrHomeFeedSpec
import net.primal.domain.feeds.mergeDefaultNoteFeeds
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.findFirstTitle
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.posts.FeedRepository

@HiltViewModel(assistedFactory = FeedListViewModel.Factory::class)
class FeedListViewModel @AssistedInject constructor(
    @Assisted private val specKind: FeedSpecKind,
    private val feedRepository: FeedRepository,
    private val feedsRepository: FeedsRepository,
    private val activeAccountStore: ActiveAccountStore,
    private val relayEventQuerier: RelayEventQuerier,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(specKind: FeedSpecKind): FeedListViewModel
    }

    private val _state = MutableStateFlow(UiState(specKind = specKind))
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private var allFeeds: List<FeedUi> = emptyList()



    init {
        observeEvents()
        observeFeeds()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.ShowFeedMarketplace -> {
                        fetchFollowSets()
                        setState {
                            copy(
                                feedMarketplaceStage = FeedMarketplaceStage.FeedMarketplace,
                            )
                        }
                    }

                    UiEvent.CloseFeedMarketplace -> setState {
                        copy(
                            feedMarketplaceStage = FeedMarketplaceStage.FeedList,
                        )
                    }

                    is UiEvent.ShowFeedDetails -> {
                        setState {
                            copy(
                                selectedDvmFeed = it.dvmFeed,
                                feedMarketplaceStage = FeedMarketplaceStage.FeedDetails,
                            )
                        }
                    }

                    UiEvent.CloseFeedDetails -> {
                        val closingDvmFeed = _state.value.selectedDvmFeed
                        if (closingDvmFeed != null) scheduleClearingDvmFeed(dvmFeed = closingDvmFeed.data)
                        setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedMarketplace) }
                    }

                    is UiEvent.AddDvmFeedToUserFeeds -> {
                        addToUserFeeds(dvmFeed = it.dvmFeed.data)
                        setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedList) }
                    }

                    is UiEvent.RemoveDvmFeedFromUserFeeds -> {
                        removeFromUserFeeds(spec = it.dvmFeed.data.buildSpec(specKind = specKind))
                        setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedList) }
                    }

                    is UiEvent.DisableFeedInUserFeeds -> {
                        updateEnabledUserFeeds(spec = it.spec, enabled = false)
                        setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedList) }
                    }

                    is UiEvent.EnableFeedInUserFeeds -> {
                        updateEnabledUserFeeds(spec = it.spec, enabled = true)
                        setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedList) }
                    }

                    is UiEvent.RemoveFeedFromUserFeeds -> {
                        removeFromUserFeeds(spec = it.spec)
                    }

                    UiEvent.OpenEditMode -> {
                        setState { copy(isEditMode = true) }
                        updateFeedsState()
                    }

                    UiEvent.CloseEditMode -> {
                        setState { copy(isEditMode = false) }
                        updateFeedsState()
                        persistLocalFeeds()
                    }

                    is UiEvent.FeedReordered -> {
                        changeAllFeeds(feeds = it.feeds)
                    }

                    is UiEvent.UpdateFeedSpecEnabled -> {
                        updateFeedSpecEnabled(feedSpec = it.feedSpec, enabled = it.enabled)
                    }

                    UiEvent.RestoreDefaultPrimalFeeds -> {
                        restoreDefaultPrimalFeeds()
                    }

                    is UiEvent.AddFollowSetFeed -> addFollowSetFeed(it.followSet)
                }
            }
        }

    private fun restoreDefaultPrimalFeeds() =
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            feedsRepository.persistLocalUserFeeds(
                userId = userId,
                specKind = specKind,
                feeds = defaultLibreNostrNoteFeeds(userId),
            )
            setState { copy(isEditMode = false) }
            updateFeedsState()
        }

    private fun observeFeeds() =
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            feedsRepository.observeFeeds(userId = userId, specKind = specKind)
                .collect { feeds -> applyObservedFeeds(userId, feeds) }
        }

    private suspend fun applyObservedFeeds(userId: String, feeds: List<PrimalFeed>) {
        if (specKind != FeedSpecKind.Notes) {
            changeAllFeeds(feeds = feeds.map { it.asFeedUi() })
            return
        }
        val relevant = feeds.filter { it.spec.isLibreNostrHomeFeedSpec() }
        val merged = mergeDefaultNoteFeeds(userId, relevant)
        if (defaultNoteFeedsNeedSync(relevant, merged)) {
            feedsRepository.persistLocalUserFeeds(
                userId = userId,
                specKind = specKind,
                feeds = merged,
            )
        }
        changeAllFeeds(feeds = merged.map { it.asFeedUi() })
    }

    private fun changeAllFeeds(feeds: List<FeedUi>) {
        allFeeds = feeds.filter { it.spec.isLibreNostrHomeFeedSpec() }
        updateFeedsState()
    }

    private fun updateFeedSpecEnabled(feedSpec: String, enabled: Boolean) {
        if (allFeeds.count { it.enabled } == 1 && !enabled) return

        val index = allFeeds.indexOfFirst { it.spec == feedSpec }
        if (index != -1) {
            allFeeds = allFeeds.toMutableList().apply {
                this[index] = this[index].copy(enabled = enabled)
            }
        }
        updateFeedsState()
    }

    private fun updateFeedsState() {
        val currentState = _state.value
        if (currentState.isEditMode) {
            setState { copy(feeds = allFeeds) }
        } else {
            setState { copy(feeds = allFeeds.filter { it.enabled }) }
        }
    }

    private fun scheduleClearingDvmFeed(dvmFeed: DvmFeed) =
        viewModelScope.launch {
            delay(400.milliseconds)
            feedRepository.removeFeedSpec(
                userId = activeAccountStore.activeUserId(),
                feedSpec = dvmFeed.buildSpec(specKind = specKind),
            )
            setState { copy(selectedDvmFeed = null) }
        }

    private fun addToUserFeeds(dvmFeed: DvmFeed) =
        viewModelScope.launch {
            feedsRepository.addDvmFeedLocally(
                userId = activeAccountStore.activeUserId(),
                dvmFeed = dvmFeed,
                specKind = specKind,
            )
        }

    private fun removeFromUserFeeds(spec: String) =
        viewModelScope.launch {
            allFeeds = allFeeds.toMutableList().apply {
                removeIf { it.spec == spec }
            }
            updateFeedsState()
            feedsRepository.removeFeedLocally(userId = activeAccountStore.activeUserId(), feedSpec = spec)
            persistLocalFeeds()
        }

    private fun updateEnabledUserFeeds(spec: String, enabled: Boolean) =
        viewModelScope.launch {
            val feedIndex = allFeeds.indexOfFirst { it.spec == spec }
            if (feedIndex != -1) {
                allFeeds = allFeeds.toMutableList().apply {
                    this[feedIndex] = this[feedIndex].copy(enabled = enabled)
                }
                updateFeedsState()
                persistLocalFeeds()
            }
        }

    private fun fetchFollowSets() =
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            val events = runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.CategorizedPeopleList.value),
                        authors = listOf(userId),
                    ),
                )
            }.getOrDefault(emptyList())
            val followSets = events
                .groupBy { it.tags.findFirstIdentifier().orEmpty() }
                .filterKeys { it.isNotBlank() }
                .mapNotNull { (dTag, group) ->
                    val latest = group.maxByOrNull { it.createdAt } ?: return@mapNotNull null
                    val members = latest.tags.pubkeyTagValues().distinct()
                    FeedListContract.FollowSetUi(
                        dTag = dTag,
                        title = latest.tags.findFirstTitle() ?: latest.content.ifBlank { dTag },
                        memberCount = members.size,
                    )
                }
                .sortedBy { it.title.lowercase() }
            setState { copy(followSets = followSets) }
        }

    private fun addFollowSetFeed(followSet: FeedListContract.FollowSetUi) =
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            val spec = buildFollowSetFeedSpec(pubkey = userId, dTag = followSet.dTag)
            if (allFeeds.any { it.spec == spec }) {
                setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedList, isEditMode = false) }
                return@launch
            }
            val newFeed = FeedUi(
                ownerId = userId,
                spec = spec,
                specKind = specKind,
                feedKind = FEED_KIND_LIST,
                title = followSet.title,
                description = "${followSet.memberCount} people",
                enabled = true,
            )
            allFeeds = allFeeds + newFeed
            updateFeedsState()
            persistLocalFeeds()
            setState { copy(feedMarketplaceStage = FeedMarketplaceStage.FeedList, isEditMode = false) }
        }

    private fun persistLocalFeeds() =
        viewModelScope.launch {
            val currentFeeds = allFeeds.map { it.asPrimalFeed() }
            val userId = activeAccountStore.activeUserId()
            feedsRepository.persistLocalUserFeeds(
                userId = userId,
                specKind = specKind,
                feeds = currentFeeds,
            )
        }
}
