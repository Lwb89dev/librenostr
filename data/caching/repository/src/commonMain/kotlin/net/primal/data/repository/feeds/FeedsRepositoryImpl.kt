package net.primal.data.repository.feeds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.local.dao.feeds.Feed
import net.primal.data.local.dao.feeds.asSpecKindFilter
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.mappers.local.asDvmFeedDO
import net.primal.data.repository.mappers.local.asDvmFeedPO
import net.primal.data.repository.mappers.local.asFeedPO
import net.primal.data.repository.mappers.local.asPrimalFeedDO
import net.primal.data.repository.mappers.local.asRecommendedCrossRefs
import net.primal.domain.feeds.AdvancedSearchParsedQuery
import net.primal.domain.feeds.DvmFeed
import net.primal.domain.feeds.FEED_KIND_DVM
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.feeds.buildSpec
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.domain.feeds.defaultLibreNostrReadFeeds
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.findFirstTitle
import net.primal.domain.nostr.utils.parseAsLNUrlOrNull
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.shared.data.local.db.withTransaction

private const val DVM_QUERY_LIMIT = 200

@Serializable
private data class RelayDvmMetadata(
    val name: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val image: String? = null,
    val lud16: String? = null,
    // NIP-89 publishers use both JSON strings and numbers for prices.
    // Keeping the raw element prevents one non-conforming publisher from
    // making the whole AppHandler event undecodable.
    val amount: JsonElement? = null,
    val subscription: Boolean? = null,
    val kind: String? = null,
    @SerialName("primal_spec") val primalSpec: String? = null,
)

private fun net.primal.domain.nostr.NostrEvent.asRelayDvmFeed(specKind: FeedSpecKind?): DvmFeed? {
    val metadata = content.decodeFromJsonStringOrNull<RelayDvmMetadata>() ?: return null
    val dvmId = tags.findFirstIdentifier() ?: return null
    val metadataKind = when (metadata.kind?.lowercase()) {
        "notes" -> FeedSpecKind.Notes
        "reads" -> FeedSpecKind.Reads
        else -> null
    }
    if (specKind != null && metadataKind != null && metadataKind != specKind) return null
    val title = metadata.name ?: tags.findFirstTitle() ?: return null
    return DvmFeed(
        eventId = id,
        dvmPubkey = pubKey,
        dvmId = dvmId,
        dvmLnUrlDecoded = metadata.lud16?.parseAsLNUrlOrNull(),
        title = title,
        description = metadata.about,
        avatarUrl = metadata.picture ?: metadata.image,
        amountInSats = metadata.amount?.jsonPrimitive?.contentOrNull,
        primalSubscriptionRequired = metadata.subscription,
        kind = specKind ?: metadataKind,
        primalSpec = metadata.primalSpec,
        isPrimalFeed = false,
    )
}

// TODO Consider splitting the repository into smaller ones
class FeedsRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : FeedsRepository {

    override fun observeAllFeeds(userId: String) =
        database.feeds().observeAllFeeds(ownerId = userId)
            .distinctUntilChanged()
            .map { it.map { it.asPrimalFeedDO() } }

    override fun observeReadsFeeds(userId: String) =
        database.feeds().observeAllFeedsBySpecKind(ownerId = userId, specKind = FeedSpecKind.Reads)
            .distinctUntilChanged()
            .map { it.map { it.asPrimalFeedDO() } }

    override fun observeNotesFeeds(userId: String) =
        database.feeds().observeAllFeedsBySpecKind(ownerId = userId, specKind = FeedSpecKind.Notes)
            .distinctUntilChanged()
            .map { it.map { it.asPrimalFeedDO() } }

    override fun observeFeeds(userId: String, specKind: FeedSpecKind) =
        database.feeds().observeAllFeedsBySpecKind(ownerId = userId, specKind = specKind)
            .distinctUntilChanged()
            .map { it.map { it.asPrimalFeedDO() } }

    override fun observeContainsFeedSpec(userId: String, feedSpec: String) =
        database.feeds().observeContainsFeed(ownerId = userId, feedSpec)

    override suspend fun fetchAndPersistArticleFeeds(userId: String) =
        fetchAndPersistFeeds(userId = userId, specKind = FeedSpecKind.Reads)

    override suspend fun fetchAndPersistNoteFeeds(userId: String) =
        fetchAndPersistFeeds(userId = userId, specKind = FeedSpecKind.Notes)

    @Suppress("UNUSED_PARAMETER")
    private suspend fun fetchAndPersistFeeds(userId: String, specKind: FeedSpecKind) = Unit

    override suspend fun persistNewDefaultFeeds(
        userId: String,
        specKind: FeedSpecKind,
        givenDefaultFeeds: List<PrimalFeed>,
    ) {
        val localFeeds = withContext(dispatcherProvider.io()) {
            database.feeds().getAllFeedsBySpecKind(ownerId = userId, specKind = specKind)
                .map { it.asPrimalFeedDO() }
        }
        val defaultFeeds = givenDefaultFeeds
            .ifEmpty { fetchDefaultFeeds(userId = userId, specKind = specKind) ?: emptyList() }

        val localFeedSpecs = localFeeds.map { it.spec }.toSet()
        val newFeeds = defaultFeeds.filterNot { localFeedSpecs.contains(it.spec) }

        if (newFeeds.isNotEmpty()) {
            val disabledNewFeeds = newFeeds.map { it.copy(enabled = false) }
            val mergedFeeds = localFeeds + disabledNewFeeds
            persistLocallyAndRemotelyUserFeeds(
                userId = userId,
                specKind = specKind,
                feeds = mergedFeeds,
            )
        }
    }

    override suspend fun fetchDefaultFeeds(userId: String, specKind: FeedSpecKind): List<PrimalFeed>? {
        // Default note feeds are application-owned Nostr specifications. They do not
        // need a server-provided list and must remain available during first launch,
        // before any relay-backed data has been persisted. Reads use the same local
        // seed for now; article pagination is a separate relay-native migration block.
        return withContext(dispatcherProvider.io()) {
            when (specKind) {
                FeedSpecKind.Notes -> defaultLibreNostrNoteFeeds(userId)
                FeedSpecKind.Reads -> defaultLibreNostrReadFeeds(userId)
            }
        }
    }

    override suspend fun persistRemotelyAllLocalUserFeeds(userId: String) {
        persistRemotelyLocalUserFeedsBySpecKind(userId = userId, specKind = FeedSpecKind.Notes)
        persistRemotelyLocalUserFeedsBySpecKind(userId = userId, specKind = FeedSpecKind.Reads)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun persistRemotelyLocalUserFeedsBySpecKind(userId: String, specKind: FeedSpecKind) = Unit

    override suspend fun persistLocalUserFeeds(
        userId: String,
        specKind: FeedSpecKind,
        feeds: List<PrimalFeed>,
    ) = withContext(dispatcherProvider.io()) {
        database.withTransaction {
            database.feeds().deleteAllByOwnerIdAndSpecKind(ownerId = userId, specKind = specKind)
            database.feeds().upsertAll(data = feeds.map { it.asFeedPO() })
        }
    }

    override suspend fun persistLocallyAndRemotelyUserFeeds(
        userId: String,
        specKind: FeedSpecKind,
        feeds: List<PrimalFeed>,
    ) = withContext(dispatcherProvider.io()) {
        persistLocalUserFeeds(userId = userId, specKind = specKind, feeds = feeds)
    }

    override suspend fun fetchAndPersistDefaultFeeds(
        userId: String,
        specKind: FeedSpecKind,
        givenDefaultFeeds: List<PrimalFeed>,
    ) = withContext(dispatcherProvider.io()) {
        val feeds = givenDefaultFeeds.ifEmpty {
            fetchDefaultFeeds(userId = userId, specKind = specKind) ?: return@withContext
        }
        persistLocallyAndRemotelyUserFeeds(userId = userId, specKind = specKind, feeds = feeds)
    }

    override suspend fun fetchRecommendedDvmFeeds(userId: String, specKind: FeedSpecKind?): List<DvmFeed> =
        withContext(dispatcherProvider.io()) {
            val events = relayEventQuerier?.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.AppHandler.value),
                    limit = DVM_QUERY_LIMIT,
                ),
            ).orEmpty()

            val dvmFeeds = events.asSequence()
                .sortedByDescending { it.createdAt }
                .mapNotNull { event -> event.asRelayDvmFeed(specKind = specKind) }
                .toList()
            val dvmFeedIds = dvmFeeds.map { it.eventId }
            val recommendedRefs = dvmFeeds.asRecommendedCrossRefs(ownerId = userId, specKind = specKind)

            database.withTransaction {
                database.dvmFeeds().upsertDvmFeedData(data = dvmFeeds.map { it.asDvmFeedPO() })
                database.dvmFeeds().deleteRecommendedByOwner(
                    ownerId = userId,
                    specKindFilter = specKind.asSpecKindFilter(),
                )
                if (dvmFeedIds.isNotEmpty()) {
                    database.dvmFeeds().upsertRecommendedCrossRefs(refs = recommendedRefs)
                }
            }
            dvmFeeds
        }

    override fun observeRecommendedDvmFeeds(userId: String, specKind: FeedSpecKind?) =
        combine(
            database.dvmFeeds().observeRecommendedDvmFeedData(
                ownerId = userId,
                specKindFilter = specKind.asSpecKindFilter(),
            ),
            database.dvmFeeds().observeFeaturedUsersByOwner(ownerId = userId),
        ) { feeds, featured ->
            val featuredByFeed = featured.groupBy { it.dvmEventId }
            feeds.map { feed ->
                feed.asDvmFeedDO(
                    featuredUserIds = featuredByFeed[feed.eventId]?.map { it.profileId } ?: emptyList(),
                )
            }
        }.distinctUntilChanged()

    override suspend fun addDvmFeedLocally(
        userId: String,
        dvmFeed: DvmFeed,
        specKind: FeedSpecKind,
    ) {
        withContext(dispatcherProvider.io()) {
            val feed = Feed(
                ownerId = userId,
                spec = dvmFeed.buildSpec(specKind = specKind),
                specKind = specKind,
                title = dvmFeed.title,
                description = dvmFeed.description ?: "",
                feedKind = FEED_KIND_DVM,
            )
            database.feeds().upsertAll(listOf(feed))
        }
    }

    override suspend fun addFeedLocally(
        userId: String,
        feedSpec: String,
        title: String,
        description: String,
        feedSpecKind: FeedSpecKind,
        feedKind: String,
    ) {
        withContext(dispatcherProvider.io()) {
            val feed = Feed(
                ownerId = userId,
                spec = feedSpec,
                specKind = feedSpecKind,
                feedKind = feedKind,
                enabled = true,
                title = title,
                description = description,
            )
            database.feeds().upsertAll(listOf(feed))
        }
    }

    override suspend fun removeFeedLocally(userId: String, feedSpec: String) {
        withContext(dispatcherProvider.io()) {
            database.feeds().deleteAllByOwnerIdAndSpec(ownerId = userId, spec = feedSpec)
        }
    }

    override suspend fun findFeedBySpec(userId: String, feedSpec: String): PrimalFeed? {
        return withContext(dispatcherProvider.io()) {
            database.feeds().findBySpec(ownerId = userId, spec = feedSpec)?.asPrimalFeedDO()
        }
    }

    override suspend fun getAdvancedSearchQuery(query: String): AdvancedSearchParsedQuery {
        return withContext(dispatcherProvider.io()) {
            parseAdvancedSearchQueryLocally(query)
        }
    }

    override suspend fun replaceFeedLocally(
        userId: String,
        oldFeedSpec: String,
        newFeedSpec: String,
        title: String,
        description: String,
        feedSpecKind: FeedSpecKind,
        feedKind: String,
    ) {
        withContext(dispatcherProvider.io()) {
            val existingFeeds = database.feeds().getAllFeedsBySpecKind(ownerId = userId, specKind = feedSpecKind)
            val oldFeed = existingFeeds.firstOrNull { it.spec == oldFeedSpec }
            val position = oldFeed?.position ?: 0

            database.withTransaction {
                database.feeds().deleteAllByOwnerIdAndSpec(ownerId = userId, spec = oldFeedSpec)
                database.feeds().upsertAll(
                    listOf(
                        Feed(
                            position = position,
                            ownerId = userId,
                            spec = newFeedSpec,
                            specKind = feedSpecKind,
                            feedKind = feedKind,
                            title = title,
                            description = description,
                            enabled = oldFeed?.enabled ?: true,
                        ),
                    ),
                )
            }
        }
    }

}
