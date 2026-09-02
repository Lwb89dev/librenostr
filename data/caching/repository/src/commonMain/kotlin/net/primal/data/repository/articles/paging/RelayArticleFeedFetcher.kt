package net.primal.data.repository.articles.paging

import io.github.aakira.napier.Napier
import net.primal.data.remote.api.articles.model.ArticleResponse
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.core.utils.runCatching
import net.primal.core.utils.getOrDefault
import net.primal.domain.common.ContentPrimalPaging
import net.primal.domain.feeds.extractAdvancedSearchQuery
import net.primal.domain.feeds.extractTopicFromFeedSpec
import net.primal.domain.feeds.extractFollowSetDTag
import net.primal.domain.feeds.extractFollowSetPubkey
import net.primal.domain.feeds.isFollowSetFeedSpec
import net.primal.domain.feeds.isReadsFeedSpec
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Loads long-form content directly from relays for the Reads feed.
 *
 * Every query here is scoped to an author set. An unscoped kind-30023 request is a global
 * firehose, and on public relays the long-form firehose is mostly spam and porn — which is
 * what a missing author list produced, because an empty list was passed as "no constraint".
 * When no author set can be resolved the feed returns empty rather than falling back to it.
 *
 * The scope is the user's follows, optionally widened to the follows of those follows when
 * follows alone cannot fill a page. That is the widest this ever goes.
 */
internal class RelayArticleFeedFetcher(
    private val querier: RelayEventQuerier,
    private val coordinator: FetchCoordinator,
) {

    private var cachedNetwork: Pair<String, List<String>>? = null

    suspend fun fetch(userId: String, feedSpec: String, limit: Int, until: Long?): ArticleResponse {
        val query = feedSpec.extractAdvancedSearchQuery()
        val topic = feedSpec.extractTopicFromFeedSpec()?.removePrefix("#")

        val authors = when {
            feedSpec.isFollowSetFeedSpec() -> loadFollowSet(
                feedSpec.extractFollowSetPubkey() ?: userId,
                feedSpec.extractFollowSetDTag() ?: return emptyResponse(),
            )
            // Topic and search feeds used to drop the author constraint entirely. They stay
            // inside the follow scope: a hashtag or a search term is a filter on the feed, not
            // a licence to read the whole network.
            else -> loadFollows(userId) + userId
        }.distinct()

        if (authors.isEmpty()) {
            Napier.d("Relay Reads: no author scope for spec=$feedSpec; refusing a global query.")
            return emptyResponse()
        }

        val searchTerm = query?.removePrefix("kind:30023")?.trim()?.takeIf { it.isNotBlank() }
        val baseFilter = RelayFilter(
            kinds = listOf(NostrEventKind.LongFormContent.value),
            authors = authors,
            hashtagTags = topic?.let { listOf(it) },
            search = searchTerm,
            limit = limit,
            until = until,
        )
        var firstPage = runCatching { querier.query(baseFilter) }.getOrDefault(emptyList())

        // Follows alone are often too few to fill a page of long-form. Widen once to the
        // follows-of-follows, still an explicit author list, never the open network.
        if (firstPage.size < limit && !feedSpec.isFollowSetFeedSpec()) {
            val network = loadFollowNetwork(userId = userId, follows = authors)
            if (network.size > authors.size) {
                val widened = runCatching { querier.query(baseFilter.copy(authors = network)) }
                    .getOrDefault(emptyList())
                firstPage = (firstPage + widened).distinctBy { it.id }
            }
        }
        // NIP-50 is optional. A broad relay query plus local matching keeps Reads
        // usable on relays which reject or ignore the search field.
        // NIP-50 is optional. When a relay ignores or rejects the search field, match locally
        // over the same author scope instead — the fallback keeps the authors, so it can never
        // turn a search into an unscoped fetch.
        val events = if (firstPage.isEmpty() && searchTerm != null) {
            val broad = runCatching { querier.query(baseFilter.copy(search = null, limit = FALLBACK_LIMIT)) }
                .getOrDefault(emptyList())
            val terms = searchTerm.split(Regex("\\s+")).filter { it.isNotBlank() }
            broad.filter { event -> terms.all { term -> event.content.contains(term, ignoreCase = true) } }
        } else {
            firstPage
        }
        val page = events.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(limit)
        val metadata = page.map { it.pubKey }.distinct().let { pubkeys ->
            if (pubkeys.isEmpty()) emptyList() else runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        authors = pubkeys,
                        limit = pubkeys.size,
                    ),
                ).latestMetadataByPubkey()
            }.getOrDefault(emptyList())
        }
        Napier.d("Relay Reads query spec=$feedSpec events=${page.size}")
        return ArticleResponse(
            paging = page.toPaging(),
            metadata = metadata,
            zaps = emptyList(),
            notes = emptyList(),
            articles = page,
            primalUserScores = emptyList(),
            referencedEvents = emptyList(),
            primalEventStats = emptyList(),
            primalEventUserStats = emptyList(),
            cdnResources = emptyList(),
            primalLinkPreviews = emptyList(),
            primalRelayHints = emptyList(),
            primalLongFormWords = emptyList(),
            primalUserNames = null,
            primalLegendProfiles = null,
            primalPremiumInfo = null,
            blossomServers = emptyList(),
        )
    }

    /** The same kind 3 the note feed wants; the coordinator makes it one request, not two. */
    private suspend fun loadFollows(userId: String): List<String> = runCatching {
        coordinator.fetchFollowList(querier = querier, pubkey = userId)
            .maxByOrNull { it.createdAt }?.tags?.pubkeyTagValues().orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun loadFollowSet(pubkey: String, dTag: String): List<String> = runCatching {
        querier.query(
            RelayFilter(kinds = listOf(NostrEventKind.CategorizedPeopleList.value), authors = listOf(pubkey)),
        ).filter { it.tags.findFirstIdentifier() == dTag }
            .maxByOrNull { it.createdAt }?.tags?.pubkeyTagValues().orEmpty()
    }.getOrDefault(emptyList())

    private fun List<NostrEvent>.toPaging() = ContentPrimalPaging(
        orderBy = "created_at",
        sinceId = minOfOrNull { it.createdAt } ?: 0,
        untilId = maxOfOrNull { it.createdAt } ?: 0,
        elements = map { it.id },
    )

    private fun emptyResponse() = ArticleResponse(
        paging = emptyList<NostrEvent>().toPaging(), metadata = emptyList(), zaps = emptyList(),
        notes = emptyList(), articles = emptyList(), primalUserScores = emptyList(),
        referencedEvents = emptyList(), primalEventStats = emptyList(), primalEventUserStats = emptyList(),
        cdnResources = emptyList(), primalLinkPreviews = emptyList(), primalRelayHints = emptyList(),
        primalLongFormWords = emptyList(), primalUserNames = null, primalLegendProfiles = null,
        primalPremiumInfo = null, blossomServers = emptyList(),
    )

    /**
     * The user's follows plus the people they follow, capped so the author array stays within
     * what relays accept. Cached per user for the lifetime of the fetcher so paging does not
     * re-derive it on every page.
     */
    private suspend fun loadFollowNetwork(userId: String, follows: List<String>): List<String> {
        cachedNetwork?.let { (cachedUserId, cached) -> if (cachedUserId == userId) return cached }

        val seeds = follows.filterNot { it == userId }.take(MAX_NETWORK_SEEDS)
        if (seeds.isEmpty()) return follows

        val secondDegree = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.FollowList.value),
                    authors = seeds,
                    limit = seeds.size,
                ),
            )
        }.getOrDefault(emptyList())
            .groupBy { it.pubKey }
            .values
            .mapNotNull { perAuthor -> perAuthor.maxByOrNull { it.createdAt } }
            .flatMap { it.tags.pubkeyTagValues() }

        val network = (follows + secondDegree).distinct().take(MAX_NETWORK_AUTHORS)
        cachedNetwork = userId to network
        Napier.d("Relay Reads: follow network built, follows=${follows.size} network=${network.size}")
        return network
    }

    companion object {
        private const val FALLBACK_LIMIT = 500

        /** Contact lists to pull when widening; each is a few KB, so this bounds the round trip. */
        private const val MAX_NETWORK_SEEDS = 150

        /** Relays commonly reject very large filter arrays; keep the author list under that. */
        private const val MAX_NETWORK_AUTHORS = 1000
    }
}
