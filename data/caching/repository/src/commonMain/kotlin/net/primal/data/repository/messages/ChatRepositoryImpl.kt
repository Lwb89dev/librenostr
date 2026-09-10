package net.primal.data.repository.messages

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.map
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.onFailure
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
import net.primal.data.repository.mappers.remote.isPrivateThreadReply
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapAsProfileDataPO
import net.primal.data.repository.messages.paging.MessagesRemoteMediator
import net.primal.data.repository.messages.processors.MessagesProcessor
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.domain.messages.ChatRepository
import net.primal.domain.messages.ConversationRelation
import net.primal.domain.messages.Nip17Message
import net.primal.domain.messages.Nip17RelayListNotFoundException
import net.primal.domain.messages.Nip17Transport
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
    private val nip17Transport: Nip17Transport? = null,
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
    private suspend fun acceptedParticipants(userId: String, currentPage: List<NostrEvent>): Set<String> {
        val writtenTo = withContext(dispatcherProvider.io()) {
            database.messages().participantsWrittenTo(ownerId = userId)
        }
        // A reply this page just discovered is not in that query yet — persisting happens after
        // this classification runs, later in the same call. Without this, the first page to ever
        // see a conversation classifies it by whether the *previous* sync had already saved the
        // reply, and a conversation whose reply and first-seen message land in the same page never
        // gets another chance: nothing revisits it once it falls outside later, narrower windows.
        val writtenToInThisPage = currentPage.participantsWrittenToBy(userId)
        val follows = relayEventQuerier?.let { querier ->
            runCatching { fetchCoordinator.fetchFollowList(querier = querier, pubkey = userId) }
                .getOrDefault(emptyList())
                .maxByOrNull { it.createdAt }
                ?.tags
                ?.pubkeyTagValues()
                .orEmpty()
        }.orEmpty()
        return writtenTo.toSet() + writtenToInThisPage + follows
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
                ).let { ConversationUpdate(conversation = it, absoluteUnreadCount = conversation.count) }
            }
            ?: response.messages.asConversationIndex(
                userId = userId,
                accepted = acceptedParticipants(userId = userId, currentPage = response.messages),
            )

        // A relay hands back kind 4 events and nothing else, so this response carries no profiles
        // and the conversation list rendered raw npubs for anyone the database had not already
        // met through the feed. The people you have talked to are the last ones who should be
        // showing up as an npub.
        val participantMetadata = relayEventQuerier?.let { querier ->
            runCatching {
                fetchCoordinator.fetchMetadata(
                    querier = querier,
                    pubkeys = messageConversation.map { it.conversation.participantId },
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
                updates = messageConversation,
            )
        }
        return response.messages
    }

    override suspend fun syncConversations(userId: String, backfillPages: Int) {
        // Not exclusive with the legacy path below: NIP-17 is new enough that a conversation can
        // easily have one side on it and the other still only reachable over the legacy encrypted
        // DM kind, so both are always fetched and merged — same as Amethyst and Damus do. Best
        // effort: a NIP-17 fetch failing here (relay down, this account genuinely has no NIP-17
        // relay list of its own yet) must not stop legacy conversations from refreshing.
        nip17Transport?.let { transport ->
            runCatching { syncNip17Messages(userId = userId, messages = transport.fetchMessages(userId)) }
                .onFailure { error -> Napier.w(throwable = error) { "NIP-17 conversation sync failed." } }
        }
        // Accumulated independently of persistence: reclassification below must not depend on
        // whether processMessageEventsAndSave has actually written this sync's messages to disk
        // by the time it runs, only on what this sync itself has seen.
        val writtenToThisSync = mutableSetOf<String>()

        // The first page is the refresh the caller asked for, so let its failure reach them.
        var messages = fetchConversations(
            userId = userId,
            relation = ConversationRelation.Follows,
            limit = SYNC_PAGE_SIZE,
        )
        writtenToThisSync += messages.participantsWrittenToBy(userId)
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
            writtenToThisSync += messages.participantsWrittenToBy(userId)
            page++
            Napier.d { "DM backfill page $page: ${messages.size} events." }
        }
        reclassifyStoredConversations(userId = userId, alsoAccepted = writtenToThisSync)
    }

    /**
     * Recomputes Follows/Other for every conversation already on disk, using only local data.
     *
     * A stored conversation's relation is only touched when its events happen to reappear in a
     * fresh relay fetch, and an old, quiet conversation never does — the sync pages ask for the
     * most recent events globally, and a conversation from months ago falls out of that window
     * long before it falls out of the database. Its relation is then whatever the very first
     * classification decided, forever, even after the reply that should flip it has been sitting
     * on disk the whole time. This sweeps every stored row against the same accepted set the fetch
     * path uses, needing nothing back from the relays to do it.
     *
     * [alsoAccepted] folds in this sync's own pages directly, rather than trusting that whatever
     * they discovered has already landed in the database by the time this runs.
     */
    private suspend fun reclassifyStoredConversations(userId: String, alsoAccepted: Set<String>) {
        val accepted = acceptedParticipants(userId = userId, currentPage = emptyList()) + alsoAccepted
        val stored = withContext(dispatcherProvider.io()) {
            database.messageConversations().findAllByOwnerId(ownerId = userId)
        }
        withContext(dispatcherProvider.io()) {
            stored.forEach { conversation ->
                database.messageConversations().updateRelation(
                    ownerId = userId,
                    participantId = conversation.participantId,
                    relation = if (conversation.participantId in accepted) {
                        ConversationRelation.Follows
                    } else {
                        ConversationRelation.Other
                    },
                )
            }
        }
    }

    private fun List<NostrEvent>.participantsWrittenToBy(userId: String): Set<String> =
        filter { it.pubKey == userId }.mapNotNull { it.tags.findFirstProfileId() }.toSet()

    override suspend fun fetchNonFollowsConversations(userId: String) {
        // See syncConversations: both protocols are fetched, not either/or.
        nip17Transport?.let { transport ->
            runCatching { syncNip17Messages(userId = userId, messages = transport.fetchMessages(userId)) }
                .onFailure { error -> Napier.w(throwable = error) { "NIP-17 conversation sync failed." } }
        }
        fetchConversations(userId = userId, relation = ConversationRelation.Other)
    }

    override suspend fun fetchNewConversationMessages(userId: String, conversationUserId: String) {
        // See syncConversations: both protocols are fetched, not either/or — the person on the
        // other end of this specific conversation may only ever reply over the legacy kind.
        nip17Transport?.let { transport ->
            runCatching {
                syncNip17Messages(
                    userId = userId,
                    messages = transport.fetchMessages(userId).filter {
                        it.senderId == conversationUserId || conversationUserId in it.recipientIds
                    },
                )
            }.onFailure { error -> Napier.w(throwable = error) { "NIP-17 message fetch failed." } }
        }
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
            if (nip17Transport == null) {
                messagesApi.markConversationAsRead(
                    body = MarkMessagesReadRequestBody(
                        authorization = authorization,
                        conversationUserId = conversationUserId,
                    ),
                )
            }
            database.messageConversations().markConversationAsRead(
                ownerId = authorization.pubKey,
                participantId = conversationUserId,
            )
        }
    }

    override suspend fun markAllMessagesAsRead(authorization: NostrEvent) {
        withContext(dispatcherProvider.io()) {
            if (nip17Transport == null) {
                messagesApi.markAllMessagesAsRead(authorization = authorization)
            }
            database.messageConversations().markAllConversationAsRead(ownerId = authorization.pubKey)
        }
    }

    override suspend fun markConversationAsReadLocally(userId: String, conversationUserId: String) {
        withContext(dispatcherProvider.io()) {
            database.messageConversations().markConversationAsRead(
                ownerId = userId,
                participantId = conversationUserId,
            )
        }
    }

    override suspend fun markAllMessagesAsReadLocally(userId: String) {
        withContext(dispatcherProvider.io()) {
            database.messageConversations().markAllConversationAsRead(ownerId = userId)
        }
    }

    override suspend fun collectNewMessages(userId: String) {
        val transport = nip17Transport ?: return
        transport.subscribeMessages(userId).collect { message ->
            syncNip17Messages(userId = userId, messages = listOf(message))
        }
    }

    override suspend fun sendMessage(
        userId: String,
        receiverId: String,
        text: String,
    ) {
        nip17Transport?.let { transport ->
            try {
                val message = transport.sendMessage(userId = userId, receiverId = receiverId, content = text)
                syncNip17Messages(userId = userId, messages = listOf(message))
                return
            } catch (error: Nip17RelayListNotFoundException) {
                // NIP-17 is new enough that most accounts on the network — including, sometimes,
                // this one — have not published a kind-10050 DM relay list yet, so sending to (or
                // as) one always failed outright with no way to reach that person at all. Falling
                // back to the legacy encrypted-DM kind keeps sending possible with anyone, the
                // same way Amethyst and Damus retain both instead of requiring NIP-17 on both ends.
                Napier.i(throwable = error) {
                    "No NIP-17 DM relay list for ${error.recipientId}; sending as a legacy DM instead."
                }
            }
        }
        sendLegacyMessage(userId = userId, receiverId = receiverId, text = text)
    }

    private suspend fun sendLegacyMessage(
        userId: String,
        receiverId: String,
        text: String,
    ) {
        val encryptedContent = messageCipher.encryptMessage(
            userId = userId,
            participantId = receiverId,
            content = text,
        )

        // Where the recipient actually reads, not just where this account writes. A kind-4 event
        // published only to the sender's own write relays never reaches someone whose relay set
        // does not overlap — the two sides both believe they are online and neither sees the
        // other's messages. Best effort: an unresolvable inbox still publishes the normal way.
        val recipientInbox = nip17Transport
            ?.let { transport -> runCatching { transport.resolveInboxRelays(receiverId) }.getOrNull() }
            .orEmpty()

        withContext(dispatcherProvider.io()) {
            val publishResult = primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    content = encryptedContent,
                    kind = NostrEventKind.EncryptedDirectMessages.value,
                    tags = listOf(receiverId.asPubkeyTag()),
                ),
                outboxRelays = recipientInbox,
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

    override suspend fun sendPrivateReply(
        userId: String,
        receiverId: String,
        text: String,
        rootId: String,
        parentId: String,
    ) {
        require(rootId.isNostrEventId() && parentId.isNostrEventId())
        val transport = checkNotNull(nip17Transport) { "NIP-17 transport is unavailable." }
        val message = transport.sendPrivateReply(
            userId = userId,
            receiverId = receiverId,
            content = text,
            threadTags = listOf(
                threadEventTag(eventId = rootId, marker = "root"),
                threadEventTag(eventId = parentId, marker = "reply"),
            ),
        )
        withContext(dispatcherProvider.io()) {
            messagesProcessor.processNip17MessagesAndSave(userId = userId, messages = listOf(message))
        }
    }

    private suspend fun syncNip17Messages(userId: String, messages: List<Nip17Message>) {
        if (messages.isEmpty()) return
        withContext(dispatcherProvider.io()) {
            messagesProcessor.processNip17MessagesAndSave(userId = userId, messages = messages)
            val directMessages = messages.filterNot { it.isPrivateThreadReply() }
            // Every sender, not only the ones who sent a chat message: a private reply raises a
            // notification naming its author, and without their kind 0 that notification says
            // "npub1abc… replied privately to your note" — which reads as spam, not as a reply
            // from somebody you know.
            cacheNip17ParticipantMetadata(userId = userId, messages = messages)
            val accepted = acceptedParticipants(userId = userId, currentPage = emptyList())
            database.messageConversations().persistConversationIndex(
                userId = userId,
                updates = directMessages.asNip17ConversationIndex(userId = userId, accepted = accepted),
            )
        }
    }

    private suspend fun cacheNip17ParticipantMetadata(userId: String, messages: List<Nip17Message>) {
        val participantIds = messages.mapNotNull { message ->
            if (message.senderId == userId) {
                message.recipientIds.firstOrNull { it != userId }
            } else {
                message.senderId
            }
        }.distinct()
        val cachedIds = database.profiles().findProfileData(participantIds).map { it.ownerId }.toSet()
        val missingIds = participantIds.filterNot { it in cachedIds }
        if (missingIds.isEmpty()) return

        val metadata = relayEventQuerier?.let { querier ->
            runCatching { fetchCoordinator.fetchMetadata(querier = querier, pubkeys = missingIds) }
                .getOrDefault(emptyList())
        }.orEmpty().latestMetadataByPubkey()
        database.profiles().insertOrUpdateAll(
            data = metadata.mapAsProfileDataPO(
                cdnResources = emptyList(),
                primalUserNames = emptyMap(),
                primalPremiumInfo = emptyMap(),
                primalLegendProfiles = emptyMap(),
                blossomServers = emptyMap(),
            ),
        )
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
        remoteMediator = nip17Transport?.let { null } ?: MessagesRemoteMediator(
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

private fun threadEventTag(eventId: String, marker: String) =
    buildJsonArray {
        add(JsonPrimitive("e"))
        add(JsonPrimitive(eventId))
        add(JsonPrimitive(""))
        add(JsonPrimitive(marker))
    }

private fun String.isNostrEventId(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

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
            ConversationUpdate(
                conversation = MessageConversationData(
                    ownerId = userId,
                    participantId = participantId,
                    participantMetadataId = null,
                    lastMessageId = latest.id,
                    lastMessageAt = latest.createdAt,
                    // See asNip17ConversationIndex: the count is derived at persist time.
                    unreadMessagesCount = 0,
                    relation = if (participantId in accepted) {
                        ConversationRelation.Follows
                    } else {
                        ConversationRelation.Other
                    },
                ),
                incomingTimestamps = events.filter { it.pubKey != userId }.map { it.createdAt },
            )
        }

private fun List<Nip17Message>.asNip17ConversationIndex(userId: String, accepted: Set<String>) =
    mapNotNull { message ->
        val receiverId = message.recipientIds.firstOrNull { it != message.senderId } ?: return@mapNotNull null
        val participantId = if (message.senderId == userId) receiverId else message.senderId
        participantId to message
    }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapNotNull { (participantId, messages) ->
            val latest = messages.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            ConversationUpdate(
                conversation = MessageConversationData(
                    ownerId = userId,
                    participantId = participantId,
                    participantMetadataId = null,
                    lastMessageId = latest.eventId,
                    lastMessageAt = latest.createdAt,
                    // Replaced by persistConversationIndex, which is the only place that knows
                    // what this account has already read.
                    unreadMessagesCount = 0,
                    relation = if (participantId in accepted) {
                        ConversationRelation.Follows
                    } else {
                        ConversationRelation.Other
                    },
                ),
                incomingTimestamps = messages.filter { it.senderId != userId }.map { it.createdAt },
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
    updates: List<ConversationUpdate>,
) {
    // An unavailable/slow relay is a valid empty response. Do not invalidate the conversation
    // PagingSource when it contains no new rows: keeping the last known local snapshot avoids
    // replacing visible conversations with the empty-state placeholder during a refresh.
    if (updates.isEmpty()) return

    val existing = findAllByOwnerId(ownerId = userId).associateBy { it.participantId }
    val newer = updates.mapNotNull { update ->
        val stored = existing[update.conversation.participantId]
        val watermark = stored?.lastMessageAt ?: Long.MIN_VALUE
        if (update.conversation.lastMessageAt <= watermark) return@mapNotNull null
        update.conversation.copy(
            unreadMessagesCount = update.unreadCountFrom(stored = stored, watermark = watermark),
        )
    }
    if (newer.isNotEmpty()) {
        upsertAll(data = newer)
    }

    // Which tab a conversation belongs in is recomputed every time, not carried by the upsert.
    // A row whose last message has not moved still has to be able to change sides: answering a
    // stranger accepts them, and rows written before this split existed all claim to be accepted.
    updates.forEach { update ->
        updateRelation(
            ownerId = userId,
            participantId = update.conversation.participantId,
            relation = update.conversation.relation,
        )
    }
}

/**
 * One conversation row, plus what this page saw arrive for it.
 *
 * The row alone cannot say how many messages are unread: that answer depends on what is already
 * stored and on what this account has already read, neither of which the code building the row
 * knows. Every builder used to write a hardcoded zero, so the Messages badge counted nothing no
 * matter how many messages arrived.
 */
private data class ConversationUpdate(
    val conversation: MessageConversationData,
    /** `created_at` of every message on this page that the *other* side sent. */
    val incomingTimestamps: List<Long> = emptyList(),
    /** Set only when a server states the count outright; then nothing is derived locally. */
    val absoluteUnreadCount: Int? = null,
)

/**
 * How many unread messages this conversation has once this page is folded in.
 *
 * Counts only what arrived strictly after the stored row's newest message, so re-processing a page
 * — which every session start does — adds nothing the second time: by then the watermark has
 * already moved past those messages. Reading a conversation zeroes the stored count, and a later
 * page with nothing newer adds zero to it, so a read conversation stays read.
 */
private fun ConversationUpdate.unreadCountFrom(stored: MessageConversationData?, watermark: Long): Int {
    absoluteUnreadCount?.let { return it }
    val newlyArrived = incomingTimestamps.count { it > watermark }
    return (stored?.unreadMessagesCount ?: 0) + newlyArrived
}
