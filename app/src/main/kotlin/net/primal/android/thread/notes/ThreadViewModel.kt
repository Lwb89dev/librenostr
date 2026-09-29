package net.primal.android.thread.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.articles.feed.ui.mapAsFeedArticleUi
import net.primal.android.thread.notes.ThreadContract.UiEvent
import net.primal.android.thread.notes.ThreadContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.map
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.events.EventRepository
import net.primal.domain.nostr.Nip19TLV
import net.primal.domain.nostr.cryptography.utils.bech32ToHexOrThrow
import net.primal.domain.posts.FeedRepository
import net.primal.domain.reads.ArticleRepository

@HiltViewModel(assistedFactory = ThreadViewModel.Factory::class)
class ThreadViewModel @AssistedInject constructor(
    @Assisted noteId: String,
    private val activeAccountStore: ActiveAccountStore,
    private val dispatcherProvider: DispatcherProvider,
    private val feedRepository: FeedRepository,
    private val eventRepository: EventRepository,
    private val articleRepository: ArticleRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(noteId: String): ThreadViewModel
    }

    private val highlightPostId = noteId.resolveNoteIdOrThrow()

    private val _state = MutableStateFlow(
        UiState(
            highlightPostId = highlightPostId,
        ),
    )
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    /**
     * When [fetchNoteReplies] last completed, successfully or not. Survives `ON_STOP`/`ON_START`
     * because the ViewModel instance does — only process death or leaving the screen for good
     * clears it, at which point a fresh instance starts `null` and fetches unconditionally.
     */
    private var lastFetchCompletedAt: Instant? = null

    init {
        observeEvents()
        observeConversationChanges()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.UpdateConversation -> fetchData(force = true)
                    UiEvent.ScreenStarted -> fetchData(force = false)
                    UiEvent.DismissError -> setState { copy(error = null) }
                }
            }
        }

    private fun observeConversationChanges() =
        viewModelScope.launch {
            var articleObserverStarted = false
            feedRepository.observeConversation(userId = activeAccountStore.activeUserId(), noteId = highlightPostId)
                .filter { it.isNotEmpty() }
                .map { posts -> posts.asDisplayOrderedFeedPostUi(highlightPostId = highlightPostId) }
                .flowOn(dispatcherProvider.io())
                .collect { conversation ->
                    setState { copy(highlightNote = conversation.find { it.postId == highlightPostId }) }

                    val highlightPostIndex = conversation.indexOfFirst { it.postId == highlightPostId }
                    if (_state.value.conversation.isEmpty() && highlightPostIndex != -1) {
                        setState {
                            copy(
                                conversation = listOf(conversation[highlightPostIndex]),
                                highlightPostIndex = 0,
                            )
                        }
                        // Delay shortly to propagate highlighted post to UI
                        delay(100.milliseconds)
                    }

                    setState {
                        copy(
                            conversation = conversation,
                            highlightPostIndex = highlightPostIndex,
                        )
                    }

                    if (!articleObserverStarted) {
                        articleObserverStarted = true
                        observeArticle()
                    }
                }
        }

    private fun observeArticle() =
        viewModelScope.launch {
            articleRepository.observeArticleByCommentId(commentNoteId = highlightPostId)
                .filterNotNull()
                .collect { article ->
                    setState { copy(replyToArticle = article.mapAsFeedArticleUi()) }
                }
        }

    private fun fetchData(force: Boolean) {
        fetchNoteReplies(force = force)
        fetchTopNoteZaps()
    }

    /**
     * [force] = false (a plain `ON_START`) skips the network round trip when the thread was
     * already fetched within [THREAD_REFRESH_FRESHNESS_WINDOW] — returning to a thread you just
     * left used to repeat the same relay chain it took to open it the first time. Pull-to-refresh
     * and the retry button always pass `force = true`, so an explicit user action is never
     * swallowed by this check. The UI stays reactive either way: `observeConversationChanges`
     * reads from Room independently of what triggered the fetch.
     */
    private fun fetchNoteReplies(force: Boolean) =
        viewModelScope.launch {
            val fresh = !force && lastFetchCompletedAt?.let {
                Clock.System.now() - it < THREAD_REFRESH_FRESHNESS_WINDOW
            } == true
            if (fresh) return@launch

            setState { copy(fetching = true) }
            try {
                withContext(dispatcherProvider.io()) {
                    feedRepository.fetchConversation(
                        userId = activeAccountStore.activeUserId(),
                        noteId = highlightPostId,
                    )
                }
                lastFetchCompletedAt = Clock.System.now()
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { "Failed to fetch note replies for noteId=$highlightPostId" }
            } finally {
                setState { copy(fetching = false) }
            }
        }

    private fun fetchTopNoteZaps() =
        viewModelScope.launch {
            try {
                withContext(dispatcherProvider.io()) {
                    eventRepository.fetchEventZaps(
                        userId = activeAccountStore.activeUserId(),
                        eventId = highlightPostId,
                        limit = 15,
                    )
                }
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { "Failed to fetch top note zaps for eventId=$highlightPostId" }
            }
        }

    private fun String.resolveNoteIdOrThrow(): String =
        when {
            this.startsWith("note1") -> runCatching { bech32ToHexOrThrow() }.getOrNull()
            this.startsWith("nevent1") -> Nip19TLV.parseUriAsNeventOrNull(this)?.eventId
            else -> this
        }.toString()

    private companion object {
        /** How long a successful fetch is trusted before a plain `ON_START` re-asks relays. */
        val THREAD_REFRESH_FRESHNESS_WINDOW = 20.seconds
    }
}
