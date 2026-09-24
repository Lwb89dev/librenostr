package net.primal.android.events.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.core.compose.attachment.model.EventUriUi
import net.primal.android.core.compose.attachment.model.asEventUriUiModel
import net.primal.android.core.files.MediaDownloader
import net.primal.android.core.files.error.UnableToSaveContent
import net.primal.android.core.files.error.UnsuccessfulFileDownload
import net.primal.android.events.gallery.EventMediaGalleryContract.UiEvent
import net.primal.android.events.gallery.EventMediaGalleryContract.UiState
import net.primal.android.navigation.mediaPositionMs
import net.primal.android.navigation.mediaUrl
import net.primal.android.navigation.noteIdOrThrow
import net.primal.android.notes.feed.model.EventStatsUi
import net.primal.android.notes.feed.model.asFeedPostUi
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.events.EventRepository
import net.primal.domain.links.EventUriRepository
import net.primal.domain.links.EventUriType
import net.primal.domain.posts.FeedRepository

@HiltViewModel
class EventMediaGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dispatcherProvider: DispatcherProvider,
    private val mediaDownloader: MediaDownloader,
    private val eventUriRepository: EventUriRepository,
    private val feedRepository: FeedRepository,
    private val eventRepository: EventRepository,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    private val noteId = savedStateHandle.noteIdOrThrow
    private val initialMediaUrl = savedStateHandle.mediaUrl
    private val initialPositionMs = savedStateHandle.mediaPositionMs

    private val _state = MutableStateFlow(UiState(noteId = noteId))
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private val _effects = Channel<EventMediaGalleryContract.SideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: EventMediaGalleryContract.SideEffect) =
        viewModelScope.launch { _effects.send(effect) }

    init {
        observeEvents()
        loadAttachments()
        loadNoteForActions()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.SaveMedia -> saveMedia(attachment = it.attachment)
                    UiEvent.DismissError -> setState { copy(error = null) }
                }
            }
        }

    private fun loadAttachments() =
        viewModelScope.launch {
            val attachments = withContext(dispatcherProvider.io()) {
                eventUriRepository.loadEventLinks(
                    noteId = noteId,
                    types = listOf(EventUriType.Image, EventUriType.Video),
                )
            }
            setState {
                copy(
                    loading = false,
                    attachments = attachments.map { it.asEventUriUiModel() },
                    initialAttachmentIndex = attachments.indexOfFirst { it.url == initialMediaUrl },
                    initialPositionMs = this@EventMediaGalleryViewModel.initialPositionMs,
                )
            }
        }

    /**
     * Fetches the note this gallery was opened from so its reply/zap/like/repost bar has
     * something to act on, then keeps its counters live for as long as the gallery is open —
     * mirrors NoteFeedViewModel.statsOverrides, just for a single, always-requested note instead
     * of a viewport-gated set. The like/zap/repost/bookmark actions themselves are handled by a
     * separate, screen-owned NoteViewModel (same one FeedNoteCard uses), not here: this ViewModel
     * only needs to supply the note to act on and keep it fresh.
     */
    private fun loadNoteForActions() =
        viewModelScope.launch {
            val post = withContext(dispatcherProvider.io()) {
                feedRepository.findPostsById(postId = noteId)
            } ?: return@launch
            val noteUi = post.asFeedPostUi()
            setState { copy(note = noteUi) }

            val userId = activeAccountStore.activeUserId()
            launch(dispatcherProvider.io()) {
                runCatching { eventRepository.fetchAndCacheEventStats(eventIds = listOf(noteId), userId = userId) }
                    .onFailure { Napier.w(throwable = it) { "Gallery note stats fetch failed." } }
            }
            combine(
                eventRepository.observeEventStats(eventIds = listOf(noteId)),
                eventRepository.observeUserEventStatus(eventIds = listOf(noteId), userId = userId),
            ) { stats, userStats ->
                val eventStats = stats.firstOrNull { it.eventId == noteId }
                val userEventStats = userStats.firstOrNull { it.eventId == noteId }
                EventStatsUi.from(eventStats, userEventStats)
            }.collect { liveStats ->
                setState { copy(note = this.note?.copy(stats = liveStats)) }
            }
        }

    private fun saveMedia(attachment: EventUriUi) =
        viewModelScope.launch {
            withContext(dispatcherProvider.io()) {
                try {
                    mediaDownloader.downloadToMediaGallery(url = attachment.url)
                    setEffect(EventMediaGalleryContract.SideEffect.MediaSaved(type = attachment.type))
                } catch (error: UnsuccessfulFileDownload) {
                    Napier.w(throwable = error) { "Failed to download media." }
                    setState { copy(error = UiState.MediaGalleryError.FailedToSaveMedia(error)) }
                } catch (error: UnableToSaveContent) {
                    Napier.w(throwable = error) { "Failed to save media." }
                    setState { copy(error = UiState.MediaGalleryError.FailedToSaveMedia(error)) }
                }
            }
        }
}
