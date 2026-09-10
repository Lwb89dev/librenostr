package net.primal.data.repository.messages.processors

import io.github.aakira.napier.Napier
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.messages.DirectMessageData
import net.primal.data.local.dao.messages.PrivateThreadReplyData
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResource
import net.primal.data.remote.mapper.mapAsMapPubkeyToListOfBlossomServers
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.mappers.remote.flatMapMessagesAsEventUriPO
import net.primal.data.repository.mappers.remote.flatMapMessagesAsReferencedNostrUriDO
import net.primal.data.repository.mappers.remote.isPrivateThreadReply
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapAsMessageDataPO
import net.primal.data.repository.mappers.remote.mapAsPostDataPO
import net.primal.data.repository.mappers.remote.mapAsPrivateThreadReplyPO
import net.primal.data.repository.mappers.remote.mapAsProfileDataPO
import net.primal.data.repository.mappers.remote.mapReferencedNostrUriAsEventUriNostrPO
import net.primal.data.repository.mappers.remote.parseAndMapPrimalLegendProfiles
import net.primal.data.repository.mappers.remote.parseAndMapPrimalPremiumInfo
import net.primal.data.repository.mappers.remote.parseAndMapPrimalUserNames
import net.primal.domain.common.PrimalEvent
import net.primal.domain.messages.Nip17Message
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.nostr.utils.extractProfileId
import net.primal.domain.nostr.utils.isNostrUri
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType
import net.primal.shared.data.local.db.withTransaction

internal class MessagesProcessor(
    private val database: CachingDatabase,
    private val messageCipher: MessageCipher,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
    private val fetchCoordinator: FetchCoordinator,
) {

    suspend fun processNip17MessagesAndSave(userId: String, messages: List<Nip17Message>) {
        val (privateReplies, directMessages) = messages.partition { it.isPrivateThreadReply() }
        val privateReplyData = privateReplies.mapNotNull { message ->
            val result = runCatching { message.mapAsPrivateThreadReplyPO(userId = userId) }
            result.exceptionOrNull()?.let { error ->
                Napier.w(error) { "Rejected private thread reply ${message.eventId}: ${error.message}" }
            }
            result.getOrNull()
        }
        val messageDataList = directMessages.mapNotNull { it.mapAsMessageDataPO(userId = userId) }
        processNostrUrisAndSave(userId = userId, messageDataList = messageDataList)
        val notifications = buildPrivateReplyNotifications(userId = userId, replies = privateReplyData)
        database.withTransaction {
            database.messages().upsertAll(data = messageDataList)
            database.privateThreadReplies().upsertAll(data = privateReplyData)
            database.notifications().upsertAll(notifications)
            database.notificationGroupCrossRef().insertAll(
                notifications.flatMap { notification ->
                    PRIVATE_REPLY_NOTIFICATION_GROUPS.map { group ->
                        NotificationGroupCrossRef(
                            notificationId = notification.notificationId,
                            ownerId = userId,
                            groupKey = group.name,
                        )
                    }
                },
            )
        }
        Napier.i {
            "NIP-17 processed: dms=${messageDataList.size}, privateReplies=${privateReplyData.size}, " +
                "notifications=${notifications.size}"
        }
    }

    /**
     * Turns incoming private replies into notification rows, so the recipient learns one arrived.
     *
     * Nothing else can tell them: the reply is encrypted inside a gift wrap, so no relay serves a
     * notification for it and it appears nowhere but inside the one thread it belongs to. Without
     * this, a private reply was delivered correctly and still went unread forever unless its
     * recipient happened to reopen exactly that conversation.
     *
     * Only replies addressed to this account by somebody else qualify — a self-copy of an outgoing
     * reply is not news — and an already-seen row keeps its timestamp so re-fetching the same gift
     * wrap (every session start does) cannot make an old reply unread again.
     */
    private suspend fun buildPrivateReplyNotifications(
        userId: String,
        replies: List<PrivateThreadReplyData>,
    ): List<NotificationData> {
        val incoming = replies.filter { it.recipientId == userId && it.senderId != userId }
        if (incoming.isEmpty()) return emptyList()

        val seenAt = database.notifications()
            .findByIds(userId, incoming.map { it.eventId })
            .associate { it.notificationId to it.seenGloballyAt }

        return incoming.map { reply ->
            NotificationData(
                notificationId = reply.eventId,
                ownerId = userId,
                createdAt = reply.createdAt,
                type = NotificationType.YOUR_POST_WAS_PRIVATELY_REPLIED_TO,
                seenGloballyAt = seenAt[reply.eventId],
                actionUserId = reply.senderId,
                actionPostId = reply.eventId,
            )
        }
    }

    suspend fun processMessageEventsAndSave(
        userId: String,
        messages: List<NostrEvent>,
        profileMetadata: List<NostrEvent>,
        mediaResources: List<PrimalEvent>,
        primalUserNames: PrimalEvent?,
        primalPremiumInfo: PrimalEvent?,
        primalLegendProfiles: PrimalEvent?,
        blossomServerEvents: List<NostrEvent>?,
    ) {
        val messageDataList = messages.mapAsMessageDataPO(
            userId = userId,
            onMessageDecrypt = messageCipher::decryptMessage,
        )

        processNostrUrisAndSave(userId = userId, messageDataList = messageDataList)

        val cdnResources = mediaResources.flatMapNotNullAsCdnResource()
        val primalUserNamesMap = primalUserNames.parseAndMapPrimalUserNames()
        val primalPremiumInfoMap = primalPremiumInfo.parseAndMapPrimalPremiumInfo()
        val primalLegendProfilesMap = primalLegendProfiles.parseAndMapPrimalLegendProfiles()
        val attachments = messageDataList.flatMapMessagesAsEventUriPO()
        val blossomServers = blossomServerEvents?.mapAsMapPubkeyToListOfBlossomServers() ?: emptyMap()

        database.withTransaction {
            database.profiles().insertOrUpdateAll(
                data = profileMetadata.mapAsProfileDataPO(
                    cdnResources = cdnResources,
                    primalUserNames = primalUserNamesMap,
                    primalPremiumInfo = primalPremiumInfoMap,
                    primalLegendProfiles = primalLegendProfilesMap,
                    blossomServers = blossomServers,
                ),
            )
            database.messages().upsertAll(data = messageDataList)
            database.eventUris().upsertAllEventUris(data = attachments)
        }
    }

    private suspend fun processNostrUrisAndSave(userId: String, messageDataList: List<DirectMessageData>) {
        val nostrUris = messageDataList.flatMap { it.uris.decrypted }.filter { it.isNostrUri() }

        val referencedEventIds = nostrUris.mapNotNull { it.extractNoteId() }.toSet()
        val localNotes = database.posts().findPosts(referencedEventIds.toList())
        val missingEventIds = referencedEventIds - localNotes.map { it.postId }.toSet()
        val remoteNotes = if (missingEventIds.isNotEmpty()) {
            val events: List<NostrEvent> = relayEventQuerier?.let { querier ->
                runCatching {
                    querier.query(RelayFilter(ids = missingEventIds.toList(), limit = missingEventIds.size))
                }.getOrDefault(emptyList())
            }.orEmpty()
            events.filter { it.kind == NostrEventKind.ShortTextNote.value }
                .mapAsPostDataPO(
                    referencedPosts = emptyList(),
                    referencedArticles = emptyList(),
                    referencedHighlights = emptyList(),
                )
        } else {
            emptyList()
        }

        val allNotes = (localNotes + remoteNotes)
        val referencedNotesMap = allNotes.groupBy { it.postId }.mapValues { it.value.first() }

        val referencedProfileIds = nostrUris.mapNotNull { it.extractProfileId() }.toSet()
        val refNoteAuthorProfileIds = allNotes.map { it.authorId }.toSet()
        val allProfileIds = referencedProfileIds + refNoteAuthorProfileIds
        val localProfiles = database.profiles().findProfileData(allProfileIds.toList())
        val missingProfileIds = allProfileIds - localProfiles.map { it.ownerId }.toSet()
        val remoteProfiles = if (missingProfileIds.isNotEmpty()) {
            // The people mentioned in a conversation are usually the people already on screen
            // elsewhere. This path used to sit outside every dedupe there is and ask again.
            val metadataEvents: List<NostrEvent> = relayEventQuerier?.let { querier ->
                runCatching {
                    fetchCoordinator.fetchMetadata(querier = querier, pubkeys = missingProfileIds.toList())
                }.getOrDefault(emptyList())
            }.orEmpty()
            val profiles = metadataEvents
                .latestMetadataByPubkey()
                .mapAsProfileDataPO(
                    cdnResources = emptyList(),
                    primalUserNames = emptyMap(),
                    primalPremiumInfo = emptyMap(),
                    primalLegendProfiles = emptyMap(),
                    blossomServers = emptyMap(),
                )
            database.profiles().insertOrUpdateAll(data = profiles)
            profiles
        } else {
            emptyList()
        }

        val referencedProfilesMap = (localProfiles + remoteProfiles)
            .groupBy { it.ownerId }
            .mapValues { it.value.first() }

        database.eventUris().upsertAllEventNostrUris(
            data = messageDataList.flatMapMessagesAsReferencedNostrUriDO(
                eventIdToNostrEvent = emptyMap(),
                postIdToPostDataMap = referencedNotesMap,
                articleIdToArticle = emptyMap(),
                profileIdToProfileDataMap = referencedProfilesMap,
                cdnResources = emptyMap(),
                linkPreviews = emptyMap(),
                videoThumbnails = emptyMap(),
                streamIdToStreamData = emptyMap(),
            ).mapReferencedNostrUriAsEventUriNostrPO(),
        )
    }

    private companion object {
        /** Tabs a private reply belongs in: the same two a public reply to your note lands in. */
        val PRIVATE_REPLY_NOTIFICATION_GROUPS = listOf(NotificationGroup.ALL, NotificationGroup.REPLIES)
    }
}
