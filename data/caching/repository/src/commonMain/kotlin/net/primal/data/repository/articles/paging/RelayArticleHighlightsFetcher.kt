package net.primal.data.repository.articles.paging

import io.github.aakira.napier.Napier
import net.primal.data.remote.api.articles.model.ArticleHighlightsResponse
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.core.utils.runCatching
import net.primal.core.utils.getOrDefault
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/** Loads NIP-84 highlights and their replies from relays. */
internal class RelayArticleHighlightsFetcher(private val querier: RelayEventQuerier) {

    suspend fun fetch(articleId: String, articleAuthorId: String): ArticleHighlightsResponse {
        val articleAddress = "${NostrEventKind.LongFormContent.value}:$articleAuthorId:$articleId"
        val highlights = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Highlight.value),
                    addressTags = listOf(articleAddress),
                    limit = HIGHLIGHT_LIMIT,
                ),
            )
        }.getOrDefault(emptyList()).distinctBy { it.id }.sortedByDescending { it.createdAt }

        val highlightIds = highlights.map { it.id }
        val comments = if (highlightIds.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.ShortTextNote.value),
                        eventTags = highlightIds,
                        limit = COMMENT_LIMIT,
                    ),
                )
            }.getOrDefault(emptyList()).distinctBy { it.id }
        }
        val zaps = if (highlightIds.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Zap.value),
                        eventTags = highlightIds,
                        limit = ZAP_LIMIT,
                    ),
                )
            }.getOrDefault(emptyList()).distinctBy { it.id }
        }

        val metadataKeys = (highlights + comments + zaps).map { it.pubKey }.distinct()
        val metadata = if (metadataKeys.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        authors = metadataKeys,
                        limit = metadataKeys.size,
                    ),
                ).latestMetadataByPubkey()
            }.getOrDefault(emptyList())
        }

        Napier.d("Relay article highlights article=$articleId highlights=${highlights.size} comments=${comments.size}")
        return ArticleHighlightsResponse(
            highlights = highlights,
            legendProfiles = null,
            primalPremiumInfo = null,
            primalUserNames = null,
            primalUserScores = null,
            cdnResources = emptyList<PrimalEvent>(),
            profileMetadatas = metadata,
            eventStats = emptyList(),
            relayHints = emptyList(),
            highlightComments = comments,
            zaps = zaps,
            primalLongFormContentWordsCount = emptyList(),
            referencedEvents = emptyList(),
            blossomServers = emptyList(),
        )
    }

    companion object {
        private const val HIGHLIGHT_LIMIT = 200
        private const val COMMENT_LIMIT = 200
        private const val ZAP_LIMIT = 200
    }
}
