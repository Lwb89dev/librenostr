package net.primal.data.repository.articles.paging

import io.github.aakira.napier.Napier
import net.primal.data.remote.api.articles.model.ArticleResponse
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.core.utils.runCatching
import net.primal.core.utils.getOrDefault
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/** Fetches a long-form event and its NIP-22 replies without the Primal cache API. */
internal class RelayArticleDetailsFetcher(private val querier: RelayEventQuerier) {

    suspend fun fetch(articleId: String, articleAuthorId: String, commentLimit: Int): ArticleResponse {
        val address = "${NostrEventKind.LongFormContent.value}:$articleAuthorId:$articleId"
        val article = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.LongFormContent.value),
                    authors = listOf(articleAuthorId),
                    addressTags = listOf(address),
                    limit = 1,
                ),
            )
                .filter { it.pubKey == articleAuthorId && it.tags.findFirstIdentifier() == articleId }
                .maxByOrNull { it.createdAt }
        }.getOrNull()

        if (article == null) {
            Napier.w("Relay article not found author=$articleAuthorId identifier=$articleId")
            return emptyResponse()
        }

        val comments = (runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.ShortTextNote.value),
                    addressTags = listOf(address),
                    limit = commentLimit,
                ),
            )
        }.getOrDefault(emptyList()) + runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.ShortTextNote.value),
                    eventTags = listOf(article.id),
                    limit = commentLimit,
                ),
            )
        }.getOrDefault(emptyList()))
            .filter { it.id != article.id }
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }

        val metadataKeys = (listOf(article) + comments).map { it.pubKey }.distinct()
        val metadata = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = metadataKeys,
                    limit = metadataKeys.size,
                ),
            ).latestMetadataByPubkey()
        }.getOrDefault(emptyList())

        Napier.d("Relay article detail id=$articleId comments=${comments.size}")
        return ArticleResponse(
            paging = null,
            metadata = metadata,
            zaps = emptyList(),
            notes = comments,
            articles = listOf(article),
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

    private fun emptyResponse() = ArticleResponse(
        paging = null,
        metadata = emptyList(),
        zaps = emptyList(),
        notes = emptyList(),
        articles = emptyList(),
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
