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
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/** Loads long-form content directly from relays for the Reads feed. */
internal class RelayArticleFeedFetcher(private val querier: RelayEventQuerier) {

    suspend fun fetch(userId: String, feedSpec: String, limit: Int, until: Long?): ArticleResponse {
        val query = feedSpec.extractAdvancedSearchQuery()
        val topic = feedSpec.extractTopicFromFeedSpec()?.removePrefix("#")
        val authors = when {
            feedSpec.isFollowSetFeedSpec() -> loadFollowSet(
                feedSpec.extractFollowSetPubkey() ?: userId,
                feedSpec.extractFollowSetDTag() ?: return emptyResponse(),
            )
            query != null || topic != null -> emptyList()
            feedSpec.isReadsFeedSpec() -> loadFollows(userId) + userId
            else -> emptyList()
        }.distinct()

        val baseFilter = RelayFilter(
            kinds = listOf(NostrEventKind.LongFormContent.value),
            authors = authors.takeIf { it.isNotEmpty() },
            eventTags = topic?.let { listOf(it) },
            search = query?.removePrefix("kind:30023")?.trim()?.takeIf { it.isNotBlank() },
            limit = limit,
            until = until,
        )
        val firstPage = runCatching { querier.query(baseFilter) }.getOrDefault(emptyList())
        // NIP-50 is optional. A broad relay query plus local matching keeps Reads
        // usable on relays which reject or ignore the search field.
        val events = if (firstPage.isEmpty() && baseFilter.search != null) {
            val broad = runCatching { querier.query(baseFilter.copy(search = null, limit = FALLBACK_LIMIT)) }
                .getOrDefault(emptyList())
            val terms = baseFilter.search.orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
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

    private suspend fun loadFollows(userId: String): List<String> = runCatching {
        querier.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.FollowList.value),
                authors = listOf(userId),
                limit = 1,
            ),
        ).maxByOrNull { it.createdAt }?.tags?.pubkeyTagValues().orEmpty()
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

    companion object {
        private const val FALLBACK_LIMIT = 500
    }
}
