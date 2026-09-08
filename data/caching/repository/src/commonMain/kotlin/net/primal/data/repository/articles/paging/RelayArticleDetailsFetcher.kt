package net.primal.data.repository.articles.paging

import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.primal.data.remote.api.articles.model.ArticleResponse
import net.primal.data.repository.feed.asReferencedPrimalEvent
import net.primal.data.repository.feed.referencedNoteIds
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

        // The article body or a comment can quote a note (a `q` tag, or a bare
        // `nostr:note1…`/`nevent1…`) — without this, that quote showed "Mentioned event not
        // found" here even when it rendered fine in the note feed/thread.
        val known = listOf(article) + comments
        val knownIds = known.map { it.id }.toSet()
        val referencedNotes = queryByIds(known.referencedNoteIds().filterNot { it in knownIds })

        val metadataSubjects = known + referencedNotes
        val metadataKeys = metadataSubjects.map { it.pubKey }.distinct()
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
            referencedEvents = referencedNotes.map { it.asReferencedPrimalEvent() },
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

    private suspend fun queryByIds(ids: List<String>): List<NostrEvent> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_CHUNK).let { chunks ->
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        runCatching {
                            querier.query(RelayFilter(ids = chunk, limit = chunk.size))
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
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

    private companion object {
        private const val ID_CHUNK = 50
    }
}
