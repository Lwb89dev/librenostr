package net.primal.data.repository.explore

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.CurrencyConversionUtils.toSats
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.explore.ExplorePopularUserCrossRef
import net.primal.data.local.dao.explore.FollowPack
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.mappers.local.asExploreTrendingTopic
import net.primal.data.repository.mappers.local.asFollowPackDO
import net.primal.data.repository.mappers.local.asProfileDataDO
import net.primal.data.repository.mappers.local.mapAsFeedPostDO
import net.primal.data.repository.mappers.remote.asProfileDataPOFromRelay
import net.primal.data.repository.mappers.remote.extractZapRequestOrNull
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapAsEventZapDO
import net.primal.data.repository.mappers.remote.mapAsPostDataPO
import net.primal.domain.common.UserProfileSearchItem
import net.primal.domain.explore.ExplorePeopleData
import net.primal.domain.explore.ExploreRepository
import net.primal.domain.explore.ExploreZapNoteData
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstProfileId
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.parseHashtags
import net.primal.domain.explore.FollowPack as FollowPackDO
import net.primal.shared.data.local.db.withTransaction

class ExploreRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier,
) : ExploreRepository {

    override suspend fun fetchTrendingZaps(userId: String): List<ExploreZapNoteData> =
        withContext(dispatcherProvider.io()) {
            val receipts = runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Zap.value),
                        since = Clock.System.now().epochSeconds - TOPIC_LOOKBACK_SECONDS,
                        limit = TRENDING_ZAP_LIMIT,
                    ),
                )
            }.getOrDefault(emptyList()).distinctBy { it.id }
            if (receipts.isEmpty()) return@withContext emptyList()

            val profileIds = receipts.flatMap { receipt ->
                listOfNotNull(receipt.tags.findFirstProfileId(), receipt.extractZapRequestOrNull()?.pubKey)
            }.distinct()
            val profiles = runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        authors = profileIds,
                        limit = profileIds.size.coerceAtMost(500),
                    ),
                )
            }.getOrDefault(emptyList()).latestMetadataByPubkey().map { it.asProfileDataPOFromRelay() }
            val profilesMap = profiles.associateBy { it.ownerId }
            val eventZaps = receipts.mapAsEventZapDO(profilesMap = profilesMap)
            val noteIds = eventZaps.map { it.eventId }.distinct()
            val notes = runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.ShortTextNote.value),
                        ids = noteIds,
                        limit = noteIds.size.coerceAtMost(500),
                    ),
                )
            }.getOrDefault(emptyList()).mapAsPostDataPO(
                referencedPosts = emptyList(),
                referencedArticles = emptyList(),
                referencedHighlights = emptyList(),
            )
            database.withTransaction {
                database.profiles().insertOrUpdateAll(data = profiles)
                database.eventZaps().upsertAll(data = eventZaps)
            }
            val notesMap = notes.associateBy { it.postId }
            eventZaps.mapNotNull { zapEvent ->
                notesMap[zapEvent.eventId]?.let { noteData ->
                    ExploreZapNoteData(
                        sender = profilesMap[zapEvent.zapSenderId]?.asProfileDataDO(),
                        receiver = profilesMap[zapEvent.zapReceiverId]?.asProfileDataDO(),
                        noteData = noteData.mapAsFeedPostDO(),
                        amountSats = zapEvent.amountInBtc.toBigDecimal().toSats(),
                        zapMessage = zapEvent.message,
                        createdAt = Instant.fromEpochSeconds(zapEvent.zapReceiptAt),
                    )
                }
            }.sortedByDescending { it.amountSats }
        }

    override suspend fun fetchTrendingPeople(userId: String): List<ExplorePeopleData> =
        withContext(dispatcherProvider.io()) {
            fetchPopularUsers().map {
                ExplorePeopleData(
                    profile = it.metadata,
                    userScore = it.score ?: 0f,
                    userFollowersCount = it.followersCount ?: 0,
                    followersIncrease = 0,
                    verifiedFollowersCount = 0,
                )
            }
        }

    override fun getFollowLists(): Flow<PagingData<FollowPackDO>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true),
            pagingSourceFactory = { database.followPacks().getFollowPacks() },
        ).flow.map { it.map { it.asFollowPackDO() } }

    override suspend fun fetchFollowLists(
        since: Long?,
        until: Long?,
        limit: Int,
        offset: Int?,
    ): List<FollowPackDO> =
        emptyList()

    override suspend fun fetchFollowList(authorId: String, identifier: String): FollowPackDO? =
        null

    override fun observeFollowList(authorId: String, identifier: String): Flow<FollowPackDO?> =
        database.followPacks().observeFollowPack(authorId = authorId, identifier = identifier)
            .map { it?.asFollowPackDO() }

    override fun observeTrendingTopics() =
        database.trendingTopics().allSortedByScore()
            .map { it.map { it.asExploreTrendingTopic() } }

    override suspend fun fetchTrendingTopics() =
        withContext(dispatcherProvider.io()) {
            val topics = run {
                val recentEvents = runCatching {
                    relayEventQuerier.query(
                        RelayFilter(
                            kinds = listOf(
                                NostrEventKind.ShortTextNote.value,
                                NostrEventKind.LongFormContent.value,
                            ),
                            since = Clock.System.now().epochSeconds - TOPIC_LOOKBACK_SECONDS,
                            limit = TOPIC_EVENT_LIMIT,
                        ),
                    )
                }.getOrDefault(emptyList())
                recentEvents.asSequence()
                    .flatMap { it.parseHashtags().asSequence() }
                    .map { it.lowercase() }
                    .filter { it.length > 1 }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(TOPIC_LIMIT)
                    .map { (topic, score) ->
                        net.primal.data.local.dao.explore.TrendingTopic(
                            topic = topic,
                            score = score.toFloat(),
                        )
                    }
            }

            if (topics.isNotEmpty()) {
                database.withTransaction {
                    database.trendingTopics().deleteAll()
                    database.trendingTopics().upsertAll(data = topics)
                }
            }
        }

    override suspend fun searchUsers(query: String, limit: Int): List<UserProfileSearchItem> =
        withContext(dispatcherProvider.io()) {
            searchUsersFromRelays(query = query, limit = limit)
        }

    private suspend fun searchUsersFromRelays(query: String, limit: Int): List<UserProfileSearchItem> {
        val normalizedQuery = query.trim().removePrefix("@").removePrefix("#").lowercase()
        if (normalizedQuery.isBlank()) return emptyList()

        val metadataLimit = (limit * 4).coerceIn(50, 200)
        val relayQuery = RelayFilter(
            kinds = listOf(NostrEventKind.Metadata.value),
            // NIP-50 lets relays search the complete metadata set instead of
            // forcing us to inspect an arbitrary first page of profiles.
            search = normalizedQuery,
            limit = metadataLimit,
        )
        val nip50Events = runCatching {
            relayEventQuerier.query(relayQuery)
        }.getOrDefault(emptyList())
        val events = nip50Events.ifEmpty {
            // Some older relays do not implement NIP-50. Keep the relay-only
            // fallback, then apply the same local predicate below. A larger
            // page is important here: matching a profile in the first 50
            // arbitrary metadata events is effectively random.
                runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        limit = FALLBACK_METADATA_LIMIT,
                    ),
                )
            }.getOrDefault(emptyList()).orEmpty()
        }

        val profiles = events
            .filter { event ->
                event.pubKey.contains(normalizedQuery, ignoreCase = true) ||
                    event.content.contains(normalizedQuery, ignoreCase = true)
            }
            .latestMetadataByPubkey()
            .map { it.asProfileDataPOFromRelay() }
            .take(limit)

        Napier.i {
            "Relay profile search query='$normalizedQuery' nip50=${nip50Events.size} " +
                "metadata=${events.size} matches=${profiles.size}"
        }

        if (profiles.isNotEmpty()) {
            database.profiles().insertOrUpdateAll(data = profiles)
        }

        return profiles.map { UserProfileSearchItem(metadata = it.asProfileDataDO()) }
    }

    override suspend fun fetchPopularUsers(): List<UserProfileSearchItem> =
        withContext(dispatcherProvider.io()) {
            val result = run {
                val followEvents = runCatching {
                    relayEventQuerier.query(
                        RelayFilter(
                            kinds = listOf(NostrEventKind.FollowList.value),
                            limit = POPULAR_FOLLOW_EVENT_LIMIT,
                        ),
                    )
                }.getOrDefault(emptyList())
                val followerScores = followEvents.asSequence()
                    .flatMap { it.tags.pubkeyTagValues().asSequence() }
                    .filter(String::isNotBlank)
                    .groupingBy { it }
                    .eachCount()
                val popularIds = followerScores.entries
                    .sortedByDescending { it.value }
                    .take(POPULAR_USER_LIMIT)
                    .map { it.key }
                val metadataEvents = runCatching {
                    relayEventQuerier.query(
                        RelayFilter(
                            kinds = listOf(NostrEventKind.Metadata.value),
                            authors = popularIds,
                            limit = popularIds.size.coerceAtLeast(POPULAR_USER_LIMIT),
                        ),
                    )
                }.getOrDefault(emptyList())
                val profiles = metadataEvents
                    .latestMetadataByPubkey()
                    .map { it.asProfileDataPOFromRelay() }
                if (profiles.isNotEmpty()) {
                    database.profiles().insertOrUpdateAll(data = profiles)
                }
                profiles.map { profile ->
                    val score = followerScores[profile.ownerId]?.toFloat() ?: 0f
                    UserProfileSearchItem(
                        metadata = profile.asProfileDataDO(),
                        score = score,
                        followersCount = score.toInt(),
                    )
                }.sortedByDescending { it.score }
            }
            if (result.isNotEmpty()) {
                database.withTransaction {
                    database.explorePopularUsers().deleteAll()
                    database.explorePopularUsers().upsertAll(
                        data = result.mapIndexed { index, item ->
                            ExplorePopularUserCrossRef(
                                profileId = item.metadata.profileId,
                                position = index,
                                score = item.score,
                            )
                        },
                    )
                }
            }
            result
        }

    override fun observePopularUsers(): Flow<List<UserProfileSearchItem>> =
        database.explorePopularUsers().observeAll()
            .map { rows ->
                rows.mapNotNull { row ->
                    row.profile?.let { profile ->
                        UserProfileSearchItem(
                            metadata = profile.asProfileDataDO(),
                            score = row.data.score,
                            followersCount = row.data.score?.toInt(),
                            isLive = row.streams.any { it.isLive() },
                        )
                    }
                }
            }
            .distinctUntilChanged()

    companion object {
        private const val PAGE_SIZE = 25
        private const val FALLBACK_METADATA_LIMIT = 500
        private const val POPULAR_FOLLOW_EVENT_LIMIT = 500
        private const val POPULAR_USER_LIMIT = 100
        private const val TRENDING_ZAP_LIMIT = 300
        private const val TOPIC_EVENT_LIMIT = 500
        private const val TOPIC_LIMIT = 30
        private const val TOPIC_LOOKBACK_SECONDS = 7 * 24 * 60 * 60L
    }
}
