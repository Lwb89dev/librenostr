package net.primal.android.messages.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.primal.android.core.compose.attachment.model.asEventUriUiModel
import net.primal.android.core.utils.usernameUiFriendly
import net.primal.android.messages.conversation.MessageConversationListContract.UiEvent
import net.primal.android.messages.conversation.MessageConversationListContract.UiState
import net.primal.android.messages.conversation.model.MessageConversationUi
import net.primal.android.notes.feed.model.asNoteNostrUriUi
import net.primal.android.premium.legend.domain.asLegendaryCustomization
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.subscriptions.SubscriptionsManager
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.messages.ChatRepository
import net.primal.domain.messages.ConversationRelation
import net.primal.domain.messages.DMConversation

@HiltViewModel
class MessageConversationListViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val subscriptionsManager: SubscriptionsManager,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val conversationsPagerCache = ConversationRelation.entries.associateWith { relation ->
        buildConversationsPager(relation = relation)
    }

    private val _state = MutableStateFlow(
        value = UiState(
            activeRelation = ConversationRelation.Follows,
            conversations = conversationsPagerCache.getValue(ConversationRelation.Follows),
        ),
    )
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    private val conversationFetchMutex = Mutex()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        subscribeToTotalUnreadCountChanges()
        fetchConversations()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.ChangeRelation -> changeRelation(relation = it.relation)
                    UiEvent.MarkAllConversationsAsRead -> markAllConversationAsRead()
                    UiEvent.ConversationsSeen -> fetchConversations()
                    UiEvent.RefreshConversations -> fetchConversations()
                }
            }
        }

    private fun subscribeToTotalUnreadCountChanges() =
        viewModelScope.launch {
            subscriptionsManager.badges
                .map { it.unreadMessagesCount }
                .distinctUntilChanged()
                .collect {
                    fetchConversations()
                }
        }

    private fun fetchConversations() =
        viewModelScope.launch {
            // Badge updates, lifecycle events and pull-to-refresh can arrive at the
            // same time. Serialize them so an older empty relay response cannot race
            // a populated response and make Paging render the empty state.
            conversationFetchMutex.withLock {
                setState { copy(loading = true) }
                try {
                    val userId = activeAccountStore.activeUserId()
                    when (state.value.activeRelation) {
                        ConversationRelation.Follows -> {
                            chatRepository.fetchFollowConversations(userId = userId)
                            chatRepository.fetchNonFollowsConversations(userId = userId)
                        }

                        ConversationRelation.Other -> {
                            chatRepository.fetchNonFollowsConversations(userId = userId)
                            chatRepository.fetchFollowConversations(userId = userId)
                        }
                    }
                } catch (error: NetworkException) {
                    Napier.w(throwable = error) { "Failed to fetch conversations" }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // Relay decoding/Room failures must not leave the screen stuck in a loading
                    // state, nor prevent a later pull-to-refresh from retrying the request.
                    Napier.w(throwable = error) { "Failed to process relay conversations" }
                } finally {
                    setState { copy(loading = false) }
                }
            }
        }

    private fun buildConversationsPager(relation: ConversationRelation) =
        chatRepository
            .newestConversations(userId = activeAccountStore.activeUserId(), relation = relation)
            .mapAsPagingDataOfMessageConversationUi()
            .cachedIn(viewModelScope + dispatcherProvider.io())

    private fun changeRelation(relation: ConversationRelation) {
        setState {
            copy(
                activeRelation = relation,
                conversations = conversationsPagerCache.getValue(relation),
            )
        }
    }

    private fun markAllConversationAsRead() {
        viewModelScope.launch(dispatcherProvider.io()) {
            runCatching {
                chatRepository.markAllMessagesAsReadLocally(userId = activeAccountStore.activeUserId())
            }.onFailure { error ->
                Napier.w(throwable = error) { "Failed to mark all conversations as read locally." }
            }
        }
    }

    private fun Flow<PagingData<DMConversation>>.mapAsPagingDataOfMessageConversationUi() =
        map { pagingData -> pagingData.map { it.mapAsMessageConversationUi() } }

    private fun DMConversation.mapAsMessageConversationUi() =
        MessageConversationUi(
            participantId = this.participant.profileId,
            participantUsername = this.participant.usernameUiFriendly(),
            lastMessageId = this.lastMessage?.messageId,
            lastMessageSnippet = this.lastMessage?.content,
            lastMessageAttachments = this.lastMessage?.links?.map { it.asEventUriUiModel() } ?: emptyList(),
            lastMessageNostrUris = this.lastMessage?.nostrUris?.map { it.asNoteNostrUriUi() } ?: emptyList(),
            lastMessageAt = this.lastMessage?.createdAt?.let { Instant.ofEpochSecond(it) },
            isLastMessageFromUser = this.lastMessage?.senderId == activeAccountStore.activeUserId(),
            participantInternetIdentifier = this.participant.internetIdentifier,
            participantAvatarCdnImage = this.participant.avatarCdnImage,
            participantLegendaryCustomization = this.participant.primalPremiumInfo
                ?.legendProfile?.asLegendaryCustomization(),
            unreadMessagesCount = this.unreadMessagesCount,
        )
}
