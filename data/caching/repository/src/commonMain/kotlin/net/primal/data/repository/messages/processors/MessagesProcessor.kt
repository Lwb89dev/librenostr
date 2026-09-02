package net.primal.data.repository.messages.processors

import net.primal.core.caching.MediaCacher
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.messages.DirectMessageData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResource
import net.primal.data.remote.mapper.mapAsMapPubkeyToListOfBlossomServers
import net.primal.data.repository.mappers.remote.flatMapMessagesAsEventUriPO
import net.primal.data.repository.mappers.remote.flatMapMessagesAsReferencedNostrUriDO
import net.primal.data.repository.mappers.remote.mapAsMessageDataPO
import net.primal.data.repository.mappers.remote.mapAsPostDataPO
import net.primal.data.repository.mappers.remote.mapAsProfileDataPO
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapReferencedNostrUriAsEventUriNostrPO
import net.primal.data.repository.mappers.remote.parseAndMapPrimalLegendProfiles
import net.primal.data.repository.mappers.remote.parseAndMapPrimalPremiumInfo
import net.primal.data.repository.mappers.remote.parseAndMapPrimalUserNames
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.nostr.utils.extractProfileId
import net.primal.domain.nostr.utils.isNostrUri
import net.primal.shared.data.local.db.withTransaction

internal class MessagesProcessor(
    private val database: CachingDatabase,
    private val messageCipher: MessageCipher,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
    private val fetchCoordinator: FetchCoordinator,
) {

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
}
