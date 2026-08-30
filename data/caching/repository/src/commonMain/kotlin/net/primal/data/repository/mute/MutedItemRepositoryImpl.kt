package net.primal.data.repository.mute

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.mutes.ListType
import net.primal.data.local.dao.mutes.MutedItemData
import net.primal.data.local.dao.mutes.MutedItemType
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.settings.SettingsApi
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResource
import net.primal.data.remote.mapper.mapAsMapPubkeyToListOfBlossomServers
import net.primal.data.repository.mappers.local.asProfileDataDO
import net.primal.data.repository.mappers.remote.asProfileDataPO
import net.primal.data.repository.mappers.remote.asProfileDataPOFromRelay
import net.primal.data.repository.mappers.remote.parseAndMapPrimalLegendProfiles
import net.primal.data.repository.mappers.remote.parseAndMapPrimalPremiumInfo
import net.primal.data.repository.mappers.remote.parseAndMapPrimalUserNames
import net.primal.domain.mutes.MutedItemRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asEventIdTag
import net.primal.domain.nostr.asFollowedMuteListPubkeyTag
import net.primal.domain.nostr.asHashtagTag
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.asWordTag
import net.primal.domain.nostr.followedMuteListIdentifierTag
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.isEventIdTag
import net.primal.domain.nostr.isFollowedMuteListTag
import net.primal.domain.nostr.isHashtagTag
import net.primal.domain.nostr.isPubKeyTag
import net.primal.domain.nostr.isWordTag
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.withTransaction

class MutedItemRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    // Nullable only for legacy unit-test construction. The Android graph always
    // supplies a relay querier, so no SettingsApi fallback is reachable there.
    private val settingsApi: SettingsApi? = null,
    private val primalPublisher: PrimalPublisher,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : MutedItemRepository {
    override fun observeMutedUsersByOwnerId(ownerId: String) =
        database.mutedItems().observeMutedUsersByOwnerId(ownerId = ownerId)
            .map { it.mapNotNull { it.profileData?.asProfileDataDO() } }

    override fun observeIsUserMutedByOwnerId(pubkey: String, ownerId: String) =
        database.mutedItems().observeIsUserMutedByOwnerId(
            pubkey = pubkey,
            ownerId = ownerId,
        )

    override fun observeMutedHashtagsByOwnerId(ownerId: String) =
        database.mutedItems()
            .observeMutedItemsByType(ownerId = ownerId, type = MutedItemType.Hashtag)
            .map { it.map { item -> item.item } }

    override fun observeMutedProfileIdsByOwnerId(ownerId: String) =
        database.mutedItems().observeMutedProfileIdsByOwnerId(ownerId = ownerId)

    override fun observeMutedWordsByOwnerId(ownerId: String) =
        database.mutedItems()
            .observeMutedItemsByType(ownerId = ownerId, type = MutedItemType.Word)
            .map { it.map { item -> item.item } }

    override fun observeIsStreamMutedByOwnerId(pubkey: String, ownerId: String) =
        database.mutedItems()
            .observeIsStreamMutedByOwnerId(ownerId = ownerId, pubkey = pubkey)

    override suspend fun fetchAndPersistMuteList(userId: String) {
        val muteList = fetchMuteListAndPersistProfiles(userId = userId)
        persistList(ownerId = userId, listType = ListType.MuteList, muteList = muteList)
    }

    override suspend fun fetchAndPersistStreamMuteList(userId: String) =
        runCatching {
            val muteList = fetchStreamMuteListAndPersistProfiles(userId = userId).getOrThrow()
            persistList(ownerId = userId, listType = ListType.StreamMuteList, muteList = muteList)
        }

    override suspend fun muteStreamNotifications(ownerId: String, pubkey: String): Result<Unit> =
        updateAndPersistStreamMuteList(userId = ownerId) {
            plus(
                MutedItemData(
                    ownerId = ownerId,
                    item = pubkey,
                    type = MutedItemType.Stream,
                    listType = ListType.StreamMuteList,
                ),
            )
        }

    override suspend fun unmuteStreamNotifications(ownerId: String, pubkey: String): Result<Unit> =
        updateAndPersistStreamMuteList(userId = ownerId) {
            minus(
                MutedItemData(
                    ownerId = ownerId,
                    item = pubkey,
                    type = MutedItemType.Stream,
                    listType = ListType.StreamMuteList,
                ),
            )
        }

    override suspend fun muteUserAndPersistMuteList(userId: String, mutedUserId: String) =
        updateAndPersistMuteList(userId = userId) {
            plus(
                MutedItemData(
                    item = mutedUserId,
                    ownerId = userId,
                    type = MutedItemType.User,
                    listType = ListType.MuteList,
                ),
            )
        }

    override suspend fun unmuteUserAndPersistMuteList(userId: String, unmutedUserId: String) =
        updateAndPersistMuteList(userId = userId) {
            minus(
                MutedItemData(
                    item = unmutedUserId,
                    ownerId = userId,
                    type = MutedItemType.User,
                    listType = ListType.MuteList,
                ),
            )
        }

    override suspend fun muteThreadAndPersistMuteList(userId: String, postId: String) {
        updateAndPersistMuteList(userId = userId) {
            plus(
                MutedItemData(
                    item = postId,
                    ownerId = userId,
                    type = MutedItemType.Thread,
                    listType = ListType.MuteList,
                ),
            )
        }
    }

    override suspend fun unmuteThreadAndPersistMuteList(userId: String, postId: String) {
        updateAndPersistMuteList(userId = userId) {
            minus(
                MutedItemData(
                    item = postId,
                    ownerId = userId,
                    type = MutedItemType.Thread,
                    listType = ListType.MuteList,
                ),
            )
        }
    }

    override suspend fun muteHashtagAndPersistMuteList(userId: String, hashtag: String) =
        updateAndPersistMuteList(userId = userId) {
            plus(
                MutedItemData(
                    item = hashtag,
                    ownerId = userId,
                    type = MutedItemType.Hashtag,
                    listType = ListType.MuteList,
                ),
            )
        }

    override suspend fun unmuteHashtagAndPersistMuteList(userId: String, hashtag: String) =
        updateAndPersistMuteList(userId = userId) {
            minus(
                MutedItemData(
                    item = hashtag,
                    ownerId = userId,
                    type = MutedItemType.Hashtag,
                    listType = ListType.MuteList,
                ),
            )
        }

    override suspend fun muteWordAndPersistMuteList(userId: String, word: String) =
        updateAndPersistMuteList(userId = userId) {
            plus(MutedItemData(item = word, ownerId = userId, type = MutedItemType.Word, listType = ListType.MuteList))
        }

    override suspend fun unmuteWordAndPersistMuteList(userId: String, word: String) =
        updateAndPersistMuteList(userId = userId) {
            minus(MutedItemData(item = word, ownerId = userId, type = MutedItemType.Word, listType = ListType.MuteList))
        }

    override suspend fun followMuteList(userId: String, muteListOwnerId: String): Result<Unit> =
        withContext(dispatcherProvider.io()) {
            runCatching {
                val existingEvent = fetchFollowedMuteListEvent(userId = userId)
                    ?.takeIf { event -> event.tags.any { it.isFollowedMuteListTag() } }
                val existingTags = existingEvent?.tags
                    ?: listOf(followedMuteListIdentifierTag())

                val alreadyFollowed = existingTags.any {
                    it.isPubKeyTag() && it.getTagValueOrNull() == muteListOwnerId
                }

                if (!alreadyFollowed) {
                    val newTags = existingTags + listOf(muteListOwnerId.asFollowedMuteListPubkeyTag())
                    primalPublisher.signPublishImportNostrEvent(
                        NostrUnsignedEvent(
                            content = existingEvent?.content ?: "",
                            pubKey = userId,
                            kind = NostrEventKind.CategorizedPeopleList.value,
                            tags = newTags,
                        ),
                    )
                }
            }
        }

    private suspend fun updateAndPersistMuteList(
        userId: String,
        reducer: Set<MutedItemData>.() -> Set<MutedItemData>,
    ) = withContext(dispatcherProvider.io()) {
        val remoteMuteList = fetchMuteListAndPersistProfiles(userId = userId)
        val newMuteList = remoteMuteList.reducer()

        primalPublisher.signPublishImportNostrEvent(
            NostrUnsignedEvent(
                content = "",
                pubKey = userId,
                kind = NostrEventKind.MuteList.value,
                tags = newMuteList.map { it.toTag() },
            ),
        )
        persistList(ownerId = userId, listType = ListType.MuteList, muteList = newMuteList)
    }

    private suspend fun updateAndPersistStreamMuteList(
        userId: String,
        reducer: Set<MutedItemData>.() -> Set<MutedItemData>,
    ) = withContext(dispatcherProvider.io()) {
        runCatching {
            val remoteStreamMuteList = fetchStreamMuteListAndPersistProfiles(userId = userId).getOrThrow()
            val newMuteList = remoteStreamMuteList.reducer()

            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    content = "",
                    pubKey = userId,
                    kind = NostrEventKind.StreamMuteList.value,
                    tags = newMuteList.map { it.toTag() },
                ),
            )

            persistList(ownerId = userId, listType = ListType.StreamMuteList, muteList = newMuteList)
        }
    }

    private suspend fun fetchStreamMuteListAndPersistProfiles(userId: String): Result<Set<MutedItemData>> =
        runCatching {
            relayEventQuerier?.let { querier ->
                val event = queryLatestListEvent(
                    querier = querier,
                    userId = userId,
                    kind = NostrEventKind.StreamMuteList,
                )
                persistRelayProfiles(event = event, querier = querier)
                return@runCatching event
                    ?.tags
                    ?.mapNotNull {
                        it.toMutedItemData(ownerId = userId, listType = ListType.StreamMuteList)
                    }
                    ?.toSet()
                    ?: emptySet()
            }

            val api = settingsApi ?: return@runCatching emptySet()
            val response = api.getStreamMuteList(userId = userId).getOrThrow()

            val muteList = response.streamMuteList
                ?.tags?.mapNotNull { it.toMutedItemData(ownerId = userId, listType = ListType.StreamMuteList) }?.toSet()
                ?: emptySet()

            val primalUserNames = response.primalUserNames.parseAndMapPrimalUserNames()
            val primalPremiumInfo = response.primalPremiumInfo.parseAndMapPrimalPremiumInfo()
            val primalLegendProfiles = response.primalLegendProfiles.parseAndMapPrimalLegendProfiles()
            val cdnResources = response.cdnResources.flatMapNotNullAsCdnResource()
            val blossomServers = response.blossomServers.mapAsMapPubkeyToListOfBlossomServers()
            val profileData = response.metadataEvents.map {
                it.asProfileDataPO(
                    cdnResources = cdnResources,
                    primalUserNames = primalUserNames,
                    primalPremiumInfo = primalPremiumInfo,
                    primalLegendProfiles = primalLegendProfiles,
                    blossomServers = blossomServers,
                )
            }

            database.profiles().insertOrUpdateAll(data = profileData)

            muteList
        }

    private suspend fun fetchMuteListAndPersistProfiles(userId: String): Set<MutedItemData> {
        relayEventQuerier?.let { querier ->
            val event = queryLatestListEvent(
                querier = querier,
                userId = userId,
                kind = NostrEventKind.MuteList,
            )
            persistRelayProfiles(event = event, querier = querier)
            return event
                ?.tags
                ?.mapNotNull { it.toMutedItemData(ownerId = userId, listType = ListType.MuteList) }
                ?.toSet()
                ?: emptySet()
        }

        val response = settingsApi?.getMuteList(userId = userId) ?: return emptySet()
        val muteList = response.muteList
            ?.tags?.mapNotNull { it.toMutedItemData(ownerId = userId, listType = ListType.MuteList) }?.toSet()
            ?: emptySet()

        val primalUserNames = response.primalUserNames.parseAndMapPrimalUserNames()
        val primalPremiumInfo = response.primalPremiumInfo.parseAndMapPrimalPremiumInfo()
        val primalLegendProfiles = response.primalLegendProfiles.parseAndMapPrimalLegendProfiles()
        val cdnResources = response.cdnResources.flatMapNotNullAsCdnResource()
        val blossomServers = response.blossomServers.mapAsMapPubkeyToListOfBlossomServers()
        val profileData = response.metadataEvents.map {
            it.asProfileDataPO(
                cdnResources = cdnResources,
                primalUserNames = primalUserNames,
                primalPremiumInfo = primalPremiumInfo,
                primalLegendProfiles = primalLegendProfiles,
                blossomServers = blossomServers,
            )
        }

        database.profiles().insertOrUpdateAll(data = profileData)

        return muteList
    }

    private suspend fun fetchFollowedMuteListEvent(userId: String): NostrEvent? {
        relayEventQuerier?.let { querier ->
            return runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.CategorizedPeopleList.value),
                        authors = listOf(userId),
                        limit = 50,
                    ),
                ).filter { event -> event.tags.any { it.isFollowedMuteListTag() } }
                    .maxByOrNull { it.createdAt }
            }.getOrNull()
        }
        return settingsApi?.getFollowedMuteListEvent(userId = userId)
    }

    private suspend fun queryLatestListEvent(
        querier: RelayEventQuerier,
        userId: String,
        kind: NostrEventKind,
    ): NostrEvent? = runCatching {
        querier.query(
            RelayFilter(
                kinds = listOf(kind.value),
                authors = listOf(userId),
                limit = 20,
            ),
        ).maxByOrNull { it.createdAt }
    }.getOrNull()

    private suspend fun persistRelayProfiles(event: NostrEvent?, querier: RelayEventQuerier) {
        val profileIds = event?.tags
            ?.mapNotNull { tag -> tag.takeIf { it.isPubKeyTag() }?.getTagValueOrNull() }
            ?.distinct()
            .orEmpty()
        if (profileIds.isEmpty()) return

        val metadata = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = profileIds,
                    limit = profileIds.size,
                ),
            )
        }.getOrDefault(emptyList<NostrEvent>())
            .map { it.asProfileDataPOFromRelay() }
        if (metadata.isNotEmpty()) {
            database.profiles().insertOrUpdateAll(data = metadata)
        }
    }

    private suspend fun persistList(
        ownerId: String,
        listType: ListType,
        muteList: Set<MutedItemData>,
    ) = withContext(dispatcherProvider.io()) {
        database.withTransaction {
            val storedList = database.mutedItems().getListByOwnerId(ownerId = ownerId, listType = listType)
            if (storedList.toSet() != muteList) {
                database.mutedItems().deleteListByOwnerId(ownerId = ownerId, listType = listType)
                database.mutedItems().upsertAll(data = muteList)
            }
        }
    }

    private fun JsonArray.toMutedItemData(ownerId: String, listType: ListType) =
        getTagValueOrNull()?.let { value ->
            findMutedType(listType = listType)?.let { type ->
                MutedItemData(
                    item = value,
                    ownerId = ownerId,
                    type = type,
                    listType = listType,
                )
            }
        }

    private fun MutedItemData.toTag() =
        when (this.type) {
            MutedItemType.User -> this.item.asPubkeyTag()
            MutedItemType.Hashtag -> this.item.asHashtagTag()
            MutedItemType.Word -> this.item.asWordTag()
            MutedItemType.Thread -> this.item.asEventIdTag()
            MutedItemType.Stream -> this.item.asPubkeyTag()
        }

    private fun JsonArray.findMutedType(listType: ListType) =
        when (listType) {
            ListType.MuteList ->
                when {
                    isPubKeyTag() -> MutedItemType.User
                    isEventIdTag() -> MutedItemType.Thread
                    isHashtagTag() -> MutedItemType.Hashtag
                    isWordTag() -> MutedItemType.Word

                    else -> null
                }

            ListType.StreamMuteList -> when {
                isPubKeyTag() -> MutedItemType.Stream
                else -> null
            }
        }
}
