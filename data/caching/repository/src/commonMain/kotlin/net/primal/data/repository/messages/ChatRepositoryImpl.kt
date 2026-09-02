package net.primal.data.repository.messages

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.map
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.messages.DirectMessage
import net.primal.data.local.dao.messages.MessageConversation
import net.primal.data.local.dao.messages.MessageConversationDao
import net.primal.data.local.dao.messages.MessageConversationData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.messages.MessagesApi
import net.primal.data.remote.api.messages.model.ConversationRequestBody
import net.primal.data.remote.api.messages.model.MarkMessagesReadRequestBody
import net.primal.data.remote.api.messages.model.MessagesRequestBody
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.fetch.FetchKey
import net.primal.data.repository.mappers.local.asDMConversation
import net.primal.data.repository.mappers.local.asDirectMessageDO
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
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
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.publisher.PrimalPublisher

@OptIn(ExperimentalPagingApi::class)
// A repository holding its collaborators. Folding them into a parameter object would add a type
// that exists only to satisfy a count, and the coordinator is deliberately not optional: a null
// one would disable coalescing silently, which is the failure this whole change is about.
@Suppress("LongParameterList")
internal class ChatRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val messageCipher: MessageCipher,
    private val messagesApi: MessagesApi,
    private val messagesProcessor: MessagesProcessor,
    private val primalPublisher: PrimalPublisher,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
    private val fetchCoordinator: FetchCoordinator,
) : ChatRepository {

    override fun newestConversations(userId: String, relation: ConversationRelation) =
        createConversationsPager {
            database.messageConversations().newestConversationsPagedByOwnerId(
                ownerId = userId,
                relation = relation,
            )
        }.flow.map { it.map { it.asDMConversation() } }

    override fun observeUnreadMessagesCount(userId: String) =
        database.messageConversations().observeUnreadMessagesCount(ownerId = userId)

    override fun newestMessages(userId: String, participantId: String) =
        createMessagesPager(userId = userId, participantId = participantId) {
            database.messages().newestMessagesPagedByOwnerId(ownerId = userId, participantId = participantId)
        }.flow.map { it.map { it.asDirectMessageDO() } }

    /**
     * Fetches one page of conversation messages and returns the events it contained.
     *
     * The caller gets the events rather than a count because the backfill decides where the next
     * page starts from the oldest event in this one.
     */
    private suspend fun fetchConversations(
        userId: String,
        relation: ConversationRelation,
        limit: Int? = null,
        until: Long? = null,
    ): List<NostrEvent> {
        // Session start pulls the newest page and so does the messages tab, so opening messages
        // right after launching runs both at once. Sharing means one set of kind 4 requests, and
        // one pass of decrypting and storing what comes back, instead of two.
        val key = FetchKey.Conversations(ownerId = userId, until = until)
        return fetchCoordinator.coalesce(key) { fetchConversationsPage(userId, relation, limit, until) }
    }

    /**
     * Who counts as a conversation you chose to be in.
     *
     * A NIP-04 event carries nothing that separates a message from a friend from one from a
     * stranger, so the split has to be decided here. Two things say you chose this conversation:
     * you follow the other person, or you have written to them. The second is what makes the
     * Others tab a request list rather than a dead end — answering somebody accepts them, and the
     * conversation moves across on the next refresh.
     *
     * The follow list comes through the coordinator, so on a screen that opens after the feed it
     * costs nothing.
     */
    private suspend fun acceptedParticipants(userId: String): Set<String> {
        val writtenTo = withContext(dispatcherProvider.io()) {
            database.messages().participantsWrittenTo(ownerId = userId)
        }
        val follows = relayEventQuerier?.let { querier ->
            runCatching { fetchCoordinator.fetchFollowList(querier = querier, pubkey = userId) }
                .getOrDefault(emptyList())
                .maxByOrNull { it.createdAt }
                ?.tags
                ?.pubkeyTagValues()
                .orEmpty()
        }.orEmpty()
        return writtenTo.toSet() + follows
    }

    private suspend fun fetchConversationsPage(
        userId: String,
        relation: ConversationRelation,
        limit: Int?,
        until: Long?,
    ): List<NostrEvent> {
        // A relay has no notion of Primal's Follows/Other conversation relation: both
        // requests query the same kind-4 event set. The list screen requests both
        // relations to keep the legacy tabs available, so skip the duplicate request
        // in relay-only mode. Two snapshots arriving in either order used to make the
        // Paging source flash and briefly render an empty state.
        if (relayEventQuerier != null && relation == ConversationRelation.Other) {
            return emptyList()
        }

        val response = withContext(dispatcherProvider.io()) {
            messagesApi.getConversations(
                body = ConversationRequestBody(
                    userId = userId,
                    relation = relation,
                    limit = limit,
                    until = until,
                ),
            )
        }
        mediaCacher?.cacheAvatarUrls(metadata = response.profileMetadata, cdnResources = response.cdnResources)
        val messageConversation = response.conversationsSummary?.summaryPerParticipantId
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
            ?: response.messages.asConversationIndex(
                userId = userId,
                accepted = acceptedParticipants(userId = userId),
            )

        // A relay hands back kind 4 events and nothing else, so this response carries no profiles
        // and the conversation list rendered raw npubs for anyone the database had not already
        // met through the feed. The people you have talked to are the last ones who should be
        // showing up as an npub.
        val participantMetadata = relayEventQuerier?.let { querier ->
            runCatching {
                fetchCoordinator.fetchMetadata(
                    querier = querier,
                    pubkeys = messageConversation.map { it.participantId },
                )
            }.getOrDefault(emptyList())
        }.orEmpty().latestMetadataByPubkey()

        withContext(dispatcherProvider.io()) {
            messagesProcessor.processMessageEventsAndSave(
                userId = userId,
                messages = response.messages,
                profileMetadata = response.profileMetadata + participantMetadata,
                mediaResources = response.cdnResources,
                primalUserNames = response.primalUserNames,
                primalPremiumInfo = response.primalPremiumInfo,
                primalLegendProfiles = response.primalLegendProfiles,
                blossomServerEvents = response.blossomServers,
            )
            database.messageConversations().persistConversationIndex(
                userId = userId,
                conversations = messageConversation,
            )
        }
        return response.messages
    }

    override suspend fun syncConversations(userId: String, backfillPages: Int) {
        // The first page is the refresh the caller asked for, so let its failure reach them.
        var messages = fetchConversations(
            userId = userId,
            relation = ConversationRelation.Follows,
            limit = SYNC_PAGE_SIZE,
        )
        var page = 0
        // A page shorter than asked for — an empty one included — is how a relay says it has
        // nothing older.
        while (messages.size >= SYNC_PAGE_SIZE && page < backfillPages) {
            // Strictly older than this page's oldest event, so the next request cannot come back
            // with the same window and stall the walk.
            val until = messages.minOf { it.createdAt } - 1
            // Backfill is best effort: history that a relay will not hand over is not a failure
            // the user needs to be told about.
            messages = runCatching {
                fetchConversations(
                    userId = userId,
                    relation = ConversationRelation.Follows,
                    limit = SYNC_PAGE_SIZE,
                    until = until,
                )
            }.getOrNull().orEmpty()
            page++
            Napier.d { "DM backfill page $page: ${messages.size} events." }
        }
    }

    override suspend fun fetchNonFollowsConversations(userId: String) {
        fetchConversations(userId = userId, relation = ConversationRelation.Other)
    }

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

/**
 * Builds the per-participant conversation rows from a page of kind-4 events.
 *
 * A relay returns messages, not Primal's conversation summary, so the index is derived here: the
 * other side of the conversation is the `p` tag when we are the author and the author otherwise.
 */
private fun List<NostrEvent>.asConversationIndex(userId: String, accepted: Set<String>) =
    mapNotNull { event ->
        val recipientId = event.tags.findFirstProfileId() ?: return@mapNotNull null
        val participantId = if (event.pubKey == userId) recipientId else event.pubKey
        participantId to event
    }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapNotNull { (participantId, events) ->
            val latest = events.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            MessageConversationData(
                ownerId = userId,
                participantId = participantId,
                participantMetadataId = null,
                lastMessageId = latest.id,
                lastMessageAt = latest.createdAt,
                unreadMessagesCount = 0,
                relation = if (participantId in accepted) {
                    ConversationRelation.Follows
                } else {
                    ConversationRelation.Other
                },
            )
        }

/**
 * Smaller than the notification page: every message on a page has to be decrypted, and a session
 * start should not spend its first seconds on ECDH for messages nobody is looking at.
 */
private const val SYNC_PAGE_SIZE = 100

/**
 * Writes the conversation index, keeping whichever row is newer.
 *
 * The upsert replaces the whole row and the backfill deliberately walks into older pages. Without
 * the comparison, a page of old messages would drag a conversation's last-message timestamp
 * backwards and reorder the inbox under the user.
 */
private suspend fun MessageConversationDao.persistConversationIndex(
    userId: String,
    conversations: List<MessageConversationData>,
) {
    // An unavailable/slow relay is a valid empty response. Do not invalidate the conversation
    // PagingSource when it contains no new rows: keeping the last known local snapshot avoids
    // replacing visible conversations with the empty-state placeholder during a refresh.
    if (conversations.isEmpty()) return

    val existing = findAllByOwnerId(ownerId = userId).associateBy { it.participantId }
    val newer = conversations.filter {
        it.lastMessageAt > (existing[it.participantId]?.lastMessageAt ?: Long.MIN_VALUE)
    }
    if (newer.isNotEmpty()) {
        upsertAll(data = newer)
    }

    // Which tab a conversation belongs in is recomputed every time, not carried by the upsert.
    // A row whose last message has not moved still has to be able to change sides: answering a
    // stranger accepts them, and rows written before this split existed all claim to be accepted.
    conversations.forEach { conversation ->
        updateRelation(
            ownerId = userId,
            participantId = conversation.participantId,
            relation = conversation.relation,
        )
    }
}
