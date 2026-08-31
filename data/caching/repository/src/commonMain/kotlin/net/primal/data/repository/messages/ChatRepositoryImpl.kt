package net.primal.data.repository.messages

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.map
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.messages.DirectMessage
import net.primal.data.local.dao.messages.MessageConversation
import net.primal.data.local.dao.messages.MessageConversationData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.messages.MessagesApi
import net.primal.data.remote.api.messages.model.ConversationRequestBody
import net.primal.data.remote.api.messages.model.MarkMessagesReadRequestBody
import net.primal.data.remote.api.messages.model.MessagesRequestBody
import net.primal.data.repository.mappers.local.asDMConversation
import net.primal.data.repository.mappers.local.asDirectMessageDO
import net.primal.data.repository.messages.paging.MessagesRemoteMediator
import net.primal.data.repository.messages.processors.MessagesProcessor
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.domain.messages.ChatRepository
import net.primal.domain.messages.ConversationRelation
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.findFirstProfileId
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.publisher.PrimalPublisher

@OptIn(ExperimentalPagingApi::class)
internal class ChatRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val messageCipher: MessageCipher,
    private val messagesApi: MessagesApi,
    private val messagesProcessor: MessagesProcessor,
    private val primalPublisher: PrimalPublisher,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : ChatRepository {

    override fun newestConversations(userId: String, relation: ConversationRelation) =
        createConversationsPager {
            database.messageConversations().newestConversationsPagedByOwnerId(
                ownerId = userId,
                relation = relation,
                // NIP-04 relay events do not carry Primal's follows/other contact
                // classification. Keep the local list visible in either tab, including
                // rows written by an older session before relay mode was enabled.
                includeAll = true,
            )
        }.flow.map { it.map { it.asDMConversation() } }

    override fun observeUnreadMessagesCount(userId: String) =
        database.messageConversations().observeUnreadMessagesCount(ownerId = userId)

    override fun newestMessages(userId: String, participantId: String) =
        createMessagesPager(userId = userId, participantId = participantId) {
            database.messages().newestMessagesPagedByOwnerId(ownerId = userId, participantId = participantId)
        }.flow.map { it.map { it.asDirectMessageDO() } }

    private suspend fun fetchConversations(userId: String, relation: ConversationRelation) {
        // A relay has no notion of Primal's Follows/Other conversation relation: both
        // requests query the same kind-4 event set. The list screen requests both
        // relations to keep the legacy tabs available, so skip the duplicate request
        // in relay-only mode. Two snapshots arriving in either order used to make the
        // Paging source flash and briefly render an empty state.
        if (relayEventQuerier != null && relation == ConversationRelation.Other) {
            return
        }

        val response = withContext(dispatcherProvider.io()) {
            messagesApi.getConversations(
                body = ConversationRequestBody(
                    userId = userId,
                    relation = relation,
                ),
            )
        }
        mediaCacher?.cacheAvatarUrls(metadata = response.profileMetadata, cdnResources = response.cdnResources)
        val summary = response.conversationsSummary
        val messageConversation = summary?.summaryPerParticipantId
            ?.map { (participantId, conversation) ->
                MessageConversationData(
                    ownerId = userId,
                    participantId = participantId,
                    participantMetadataId = response.profileMetadata
                        .find { it.pubKey == participantId }
                        ?.id,
                    lastMessageId = conversation.lastMessageId,
                    lastMessageAt = conversation.lastMessageAt,
                    unreadMessagesCount = conversation.count,
                    relation = relation,
                )
            }
            ?: response.messages
                .mapNotNull { event ->
                    val recipientId = event.tags.findFirstProfileId() ?: return@mapNotNull null
                    val participantId = if (event.pubKey == userId) recipientId else event.pubKey
                    participantId to event
                }
                .groupBy { it.first }
                .mapNotNull { (participantId, events) ->
                    val latest = events.maxByOrNull { it.second.createdAt } ?: return@mapNotNull null
                    MessageConversationData(
                        ownerId = userId,
                        participantId = participantId,
                        participantMetadataId = null,
                        lastMessageId = latest.second.id,
                        lastMessageAt = latest.second.createdAt,
                        unreadMessagesCount = 0,
                        relation = relation,
                    )
                }

        withContext(dispatcherProvider.io()) {
            messagesProcessor.processMessageEventsAndSave(
                userId = userId,
                messages = response.messages,
                profileMetadata = response.profileMetadata,
                mediaResources = response.cdnResources,
                primalUserNames = response.primalUserNames,
                primalPremiumInfo = response.primalPremiumInfo,
                primalLegendProfiles = response.primalLegendProfiles,
                blossomServerEvents = response.blossomServers,
            )
            // An unavailable/slow relay is a valid empty response. Do not invalidate
            // the conversation PagingSource when it contains no new rows: keeping the
            // last known local snapshot avoids replacing visible conversations with
            // the empty-state placeholder during a refresh.
            if (messageConversation.isNotEmpty()) {
                database.messageConversations().upsertAll(data = messageConversation)
            }
        }
    }

    override suspend fun fetchFollowConversations(userId: String) =
        fetchConversations(
            userId = userId,
            relation = ConversationRelation.Follows,
        )

    override suspend fun fetchNonFollowsConversations(userId: String) =
        fetchConversations(
            userId = userId,
            relation = ConversationRelation.Other,
        )

    override suspend fun fetchNewConversationMessages(userId: String, conversationUserId: String) {
        withContext(dispatcherProvider.io()) {
            val latestMessage = database.messages().firstByOwnerId(ownerId = userId, participantId = conversationUserId)
            val response = messagesApi.getMessages(
                body = MessagesRequestBody(
                    userId = userId,
                    participantId = conversationUserId,
                    since = latestMessage?.createdAt ?: 0,
                ),
            )
            mediaCacher?.cacheAvatarUrls(metadata = response.profileMetadata, cdnResources = response.cdnResources)
            messagesProcessor.processMessageEventsAndSave(
                userId = userId,
                messages = response.messages,
                profileMetadata = response.profileMetadata,
                mediaResources = response.cdnResources,
                primalUserNames = response.primalUserNames,
                primalPremiumInfo = response.primalPremiumInfo,
                primalLegendProfiles = response.primalLegendProfiles,
                blossomServerEvents = response.blossomServers,
            )
        }
    }

    override suspend fun markConversationAsRead(authorization: NostrEvent, conversationUserId: String) {
        withContext(dispatcherProvider.io()) {
            messagesApi.markConversationAsRead(
                body = MarkMessagesReadRequestBody(
                    authorization = authorization,
                    conversationUserId = conversationUserId,
                ),
            )
            database.messageConversations().markConversationAsRead(
                ownerId = authorization.pubKey,
                participantId = conversationUserId,
            )
        }
    }

    override suspend fun markAllMessagesAsRead(authorization: NostrEvent) {
        withContext(dispatcherProvider.io()) {
            messagesApi.markAllMessagesAsRead(authorization = authorization)
            database.messageConversations().markAllConversationAsRead(ownerId = authorization.pubKey)
        }
    }

    override suspend fun markAllMessagesAsReadLocally(userId: String) {
        withContext(dispatcherProvider.io()) {
            database.messageConversations().markAllConversationAsRead(ownerId = userId)
        }
    }

    override suspend fun sendMessage(
        userId: String,
        receiverId: String,
        text: String,
    ) {
        val encryptedContent = messageCipher.encryptMessage(
            userId = userId,
            participantId = receiverId,
            content = text,
        )

        withContext(dispatcherProvider.io()) {
            val publishResult = primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    content = encryptedContent,
                    kind = NostrEventKind.EncryptedDirectMessages.value,
                    tags = listOf(receiverId.asPubkeyTag()),
                ),
            )
            messagesProcessor.processMessageEventsAndSave(
                userId = userId,
                messages = listOf(publishResult.nostrEvent),
                profileMetadata = emptyList(),
                mediaResources = emptyList(),
                primalUserNames = null,
                primalPremiumInfo = null,
                primalLegendProfiles = null,
                blossomServerEvents = emptyList(),
            )
        }
    }

    private fun createConversationsPager(pagingSourceFactory: () -> PagingSource<Int, MessageConversation>) =
        Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 100,
                initialLoadSize = 200,
                enablePlaceholders = true,
            ),
            pagingSourceFactory = pagingSourceFactory,
        )

    private fun createMessagesPager(
        userId: String,
        participantId: String,
        pagingSourceFactory: () -> PagingSource<Int, DirectMessage>,
    ) = Pager(
        config = PagingConfig(
            pageSize = 50,
            prefetchDistance = 100,
            initialLoadSize = 200,
            enablePlaceholders = true,
        ),
        remoteMediator = MessagesRemoteMediator(
            userId = userId,
            participantId = participantId,
            dispatcherProvider = dispatcherProvider,
            database = database,
            messagesApi = messagesApi,
            messagesProcessor = messagesProcessor,
            mediaCacher = mediaCacher,
        ),
        pagingSourceFactory = pagingSourceFactory,
    )
}
