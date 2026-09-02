package net.primal.android.notes.feed.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import net.primal.android.notes.feed.list.NoteFeedContract.UiEvent
import net.primal.android.notes.feed.list.NoteFeedContract.UiState
import net.primal.android.notes.feed.model.FeedPostsSyncStats
import net.primal.android.notes.feed.model.StreamsSyncStats
import net.primal.android.notes.feed.model.asFeedPostUi
import net.primal.android.profile.domain.mapAsProfileDataDO
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.wallet.repository.ExchangeRateHandler
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResource
import net.primal.data.remote.mapper.mapAsMapPubkeyToListOfBlossomServers
import net.primal.data.repository.mappers.remote.parseAndMapPrimalLegendProfiles
import net.primal.data.repository.mappers.remote.parseAndMapPrimalPremiumInfo
import net.primal.data.repository.mappers.remote.parseAndMapPrimalUserNames
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.feeds.isPremiumFeedSpec
import net.primal.domain.feeds.supportsUpwardsNotesPagination
import net.primal.domain.mutes.MutedItemRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.findFirstEventId
import net.primal.domain.posts.FeedPageSnapshot
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedRepository
import net.primal.domain.posts.FeedRepository.Companion.INITIAL_PAGE_SIZE
import net.primal.domain.streams.StreamRepository

@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = NoteFeedViewModel.Factory::class)
class NoteFeedViewModel @AssistedInject constructor(
    @Assisted private val feedSpec: String,
    @Assisted("allowMutedThreads") private val allowMutedThreads: Boolean,
    @Assisted("showStreamsInNewPill") private val showStreamsInNewPill: Boolean,
    private val feedRepository: FeedRepository,
    private val activeAccountStore: ActiveAccountStore,
    private val mutedItemRepository: MutedItemRepository,
    private val streamRepository: StreamRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val exchangeRateHandler: ExchangeRateHandler,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            feedSpec: String,
            @Assisted("allowMutedThreads") allowMutedThreads: Boolean,
            @Assisted("showStreamsInNewPill") showStreams: Boolean,
        ): NoteFeedViewModel
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun buildFeedByDirective(feedSpec: String) =
        activeAccountStore.activeUserId
            .filter { it.isNotBlank() }
            .flatMapLatest { userId ->
                feedRepository.feedBySpec(
                    userId = userId,
                    feedSpec = feedSpec,
                    allowMutedThreads = allowMutedThreads,
                )
            }
            .map { paging -> paging.map { feedNote -> feedNote.asFeedPostUi() } }
            .cachedIn(viewModelScope + dispatcherProvider.io())

    private val _state = MutableStateFlow(UiState(notes = buildFeedByDirective(feedSpec)))
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private var latestFeedResponse: FeedPageSnapshot? = null
    private var topVisibleNote: Pair<String, String?>? = null

    private var pollingJob: Job? = null

    init {
        if (showStreamsInNewPill) {
            observeLiveEventsFromFollows()
        }
        subscribeToEvents()
        observeActiveAccount()
        observeMutedUsers()
        fetchExchangeRate()
    }

    private fun fetchExchangeRate() =
        viewModelScope.launch {
            exchangeRateHandler.updateExchangeRate(userId = activeAccountStore.activeUserId())
        }


    private fun observeMutedUsers() =
        viewModelScope.launch {
            mutedItemRepository.observeMutedProfileIdsByOwnerId(ownerId = activeAccountStore.activeUserId())
                .collect {
                    setState { copy(mutedProfileIds = it) }
                }
        }

    private fun observeLiveEventsFromFollows() =
        viewModelScope.launch {
            streamRepository.observeLiveEventsFromFollows(userId = activeAccountStore.activeUserId())
                .collect { streams ->
                    setState {
                        copy(
                            streamsSyncStats = StreamsSyncStats(
                                streamsCount = streams.size,
                                streamAvatarCdnImages = streams.mapNotNull { it.mainHostProfile?.avatarCdnImage }
                                    .distinct()
                                    .take(MAX_AVATARS),
                            ),
                        )
                    }
                }
        }

    private fun observeActiveAccount() {
        viewModelScope.launch {
            activeAccountStore.activeUserAccount.collect {
                setState {
                    copy(paywall = feedSpec.isPremiumFeedSpec())
                }
            }
        }
    }

    private fun subscribeToEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.FeedScrolledToTop -> handleScrolledToTop()
                    UiEvent.StartPolling -> startPollingIfSupported()
                    UiEvent.StopPolling -> stopPolling()
                    UiEvent.AutoUpdateFeed -> refreshFeedForVisibility()
                    UiEvent.NewPostsPillClick -> showLatestNotesAndScrollToTop()
                    is UiEvent.UpdateCurrentTopVisibleNote -> {
                        topVisibleNote = it.noteId to it.repostId
                    }
                }
            }
        }

    /**
     * Watches for new notes with a live relay subscription instead of a short poll.
     *
     * The subscription is the trigger, not the content: an arriving event only says "something
     * new exists", and the existing snapshot fetch still produces what the new-notes pill shows.
     * That keeps the visible behaviour identical while cutting the delay from up to a poll
     * interval down to about a second, and sending nothing to the relays while nothing happens.
     *
     * A slow refresh stays underneath it. A subscription can die quietly — a relay drops it, a
     * filter is capped, the author list is longer than the streamed slice — and a feed that
     * silently stops updating is worse than one that updates late.
     */
    private fun startPollingIfSupported() {
        if (!feedSpec.supportsUpwardsNotesPagination()) return

        pollingJob = viewModelScope.launch(dispatcherProvider.io()) {
            launch {
                runCatching {
                    feedRepository.streamNewNotes(
                        userId = activeAccountStore.activeUserId(),
                        feedSpec = feedSpec,
                    )
                        // New notes arrive in bursts; one refresh per burst is enough.
                        .debounce(STREAM_DEBOUNCE_MILLIS)
                        .collect {
                            runCatching { fetchLatestNotes() }
                                .onFailure { Napier.w(throwable = it) { "Latest notes refresh failed." } }
                        }
                }.onFailure { Napier.w(throwable = it) { "New note subscription ended." } }
            }

            launch {
                while (isActive) {
                    delay(SAFETY_REFRESH_INTERVAL_SECONDS.seconds)
                    runCatching { fetchLatestNotes() }
                        .onFailure { Napier.w(throwable = it) { "Safety refresh failed." } }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
    }

    private fun refreshFeedForVisibility() {
        viewModelScope.launch(dispatcherProvider.io()) {
            try {
                val snapshot = feedRepository.fetchFeedPageSnapshot(
                    userId = activeAccountStore.activeUserId(),
                    feedSpec = feedSpec,
                    limit = INITIAL_PAGE_SIZE,
                )
                // Persist the relay snapshot so both newly fetched notes and their interaction
                // stats become visible immediately after returning from a thread.
                feedRepository.replaceFeed(
                    userId = activeAccountStore.activeUserId(),
                    feedSpec = feedSpec,
                    snapshot = snapshot,
                )
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { "Automatic feed update failed for feedSpec=$feedSpec" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Napier.w(throwable = error) { "Automatic feed update failed for feedSpec=$feedSpec" }
            }
        }
    }

    private fun handleScrolledToTop() =
        viewModelScope.launch {
            if (_state.value.notesSyncStats.isTopVisibleNoteTheLatestNote()) {
                setState { copy(notesSyncStats = FeedPostsSyncStats()) }
            }
        }

    private fun FeedPostsSyncStats.isTopVisibleNoteTheLatestNote(): Boolean =
        topVisibleNote?.let { topVisibleNote ->
            latestNoteIds.firstOrNull()?.let { newestNoteId ->
                val (noteId, repostId) = topVisibleNote

                newestNoteId == noteId || newestNoteId == repostId
            }
        } == true

    /**
     * Asks the relays for what arrived since the newest note already stored, not for the whole
     * head of the feed.
     *
     * This runs on every burst the live subscription reports, and it used to re-request a full
     * page across the entire follow list each time — the same notes the database already had,
     * fetched again to work out that a handful were new. `since` moves that comparison to the
     * relays, where it costs nothing.
     *
     * The bound is deliberately inclusive. Nostr timestamps are whole seconds and notes published
     * inside the same second are common, so asking for strictly-newer would silently drop a note
     * sharing its timestamp with the newest one held. The count below already discards the note it
     * matches by id, so the overlap costs one row and no correctness.
     */
    private suspend fun fetchLatestNotes() {
        val userId = activeAccountStore.activeUserId()
        val newestLocalNote = feedRepository
            .findNewestPosts(userId = userId, feedDirective = feedSpec, limit = 1)
            .firstOrNull()

        val latestFeedPageResponse = feedRepository.fetchFeedPageSnapshot(
            userId = userId,
            feedSpec = feedSpec,
            since = newestLocalNote?.newestTimestampSeconds(),
        )

        latestFeedResponse = latestFeedPageResponse
        latestFeedPageResponse.processSyncCount(newestLocalNote = newestLocalNote)
    }

    /**
     * The newest moment this feed knows about, reposts included.
     *
     * A repost carries its own timestamp and is what the feed sorts by, so taking only the note's
     * own timestamp would ask again for everything reposted since.
     */
    private fun FeedPost.newestTimestampSeconds(): Long? =
        (reposts.mapNotNull { it.repostCreatedAt } + listOfNotNull(timestamp?.epochSeconds)).maxOrNull()

    private fun FeedPageSnapshot.processSyncCount(newestLocalNote: FeedPost? = null) {
        val allReferencedNotes = this.referencedEvents.mapNotNull {
            it.content.decodeFromJsonStringOrNull<NostrEvent>()
        }

        val repostedNotes = this.reposts
            .mapNotNull { repostEvent ->
                val noteId = repostEvent.tags.findFirstEventId()
                allReferencedNotes.find { noteId == it.id }?.let {
                    repostEvent.createdAt to it
                }
            }

        val notes = this.notes.map { it.createdAt to it }
        val latestTimestamp = (
            (newestLocalNote?.reposts?.mapNotNull { it.repostCreatedAt } ?: emptyList()) +
                listOfNotNull(newestLocalNote?.timestamp?.epochSeconds)
            ).maxOrNull()

        val allNotes = (repostedNotes + notes)
            .asSequence()
            .sortedByDescending { it.first }
            .filter { it.first >= (latestTimestamp ?: 0) }
            .distinctBy { it.second.id }
            .filter { it.second.id != newestLocalNote?.eventId }
            .map { it.second }
            .toMutableSet()

        val cdnResources = this.cdnResources.flatMapNotNullAsCdnResource()
        val primalUserNames = this.primalUserNames.parseAndMapPrimalUserNames()
        val primalPremiumInfo = this.primalPremiumInfo.parseAndMapPrimalPremiumInfo()
        val primalLegendProfiles = this.primalLegendProfiles.parseAndMapPrimalLegendProfiles()
        val blossomServers = this.blossomServers.mapAsMapPubkeyToListOfBlossomServers()
        val profiles = this.metadata.mapAsProfileDataDO(
            cdnResources = cdnResources,
            primalUserNames = primalUserNames,
            primalPremiumInfo = primalPremiumInfo,
            primalLegendProfiles = primalLegendProfiles,
            blossomServers = blossomServers,
        )

        val allNotesFromNotMutedProfiles = allNotes.filter { note -> note.pubKey !in state.value.mutedProfileIds }

        val latestAvatars = allNotesFromNotMutedProfiles
            .mapNotNull { note -> profiles.find { it.profileId == note.pubKey } }
            .mapNotNull { profileData -> profileData.avatarCdnImage }
            .distinct()

        val limit = latestAvatars.count().coerceAtMost(MAX_AVATARS)

        val newSyncStats = FeedPostsSyncStats(
            latestNotesCount = allNotesFromNotMutedProfiles.size,
            latestNoteIds = allNotesFromNotMutedProfiles.map { it.id },
            latestAvatarCdnImages = latestAvatars.take(limit),
        )

        if (newSyncStats.isTopVisibleNoteTheLatestNote() || latestTimestamp == null) {
            setState { copy(notesSyncStats = FeedPostsSyncStats()) }
        } else {
            setState { copy(notesSyncStats = newSyncStats) }
        }
    }

    private fun showLatestNotesAndScrollToTop() =
        viewModelScope.launch {
            latestFeedResponse?.let { latestFeed ->
                feedRepository.replaceFeed(
                    userId = activeAccountStore.activeUserId(),
                    feedSpec = feedSpec,
                    snapshot = latestFeed,
                )
            }

            delay(187.milliseconds)
            setState { copy(notesSyncStats = FeedPostsSyncStats(), shouldAnimateScrollToTop = true) }

            viewModelScope.launch {
                delay(1.seconds)
                setState { copy(shouldAnimateScrollToTop = false) }
            }
        }

    companion object {
        private const val MAX_AVATARS = 3
        /** Collapses a burst of arriving notes into a single refresh. */
        private const val STREAM_DEBOUNCE_MILLIS = 1_000L

        /** Backstop for a subscription that dies quietly; far rarer than the old 30s poll. */
        private const val SAFETY_REFRESH_INTERVAL_SECONDS = 300
    }
}
