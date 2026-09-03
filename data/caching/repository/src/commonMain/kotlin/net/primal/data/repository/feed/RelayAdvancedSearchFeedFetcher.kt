package net.primal.data.repository.feed

import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import io.github.aakira.napier.Napier
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.feeds.extractAdvancedSearchQuery
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.assureValidPubKeyHex
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Executes the advanced-search command against relays.
 *
 * Primal's former parser returned a server-specific command that was then sent
 * back to the cache API. The UI already emits a readable command, so we map its
 * standard portions to NIP-50/REQ filters here and keep the final text matching
 * local. This also gives useful results on relays that do not implement NIP-50.
 */
internal class RelayAdvancedSearchFeedFetcher(
    private val querier: RelayEventQuerier,
    private val coordinator: FetchCoordinator,
) {

    suspend fun fetch(
        userId: String,
        feedSpec: String,
        fallbackKinds: List<Int>,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
    ): FeedResponse {
        val query = feedSpec.extractAdvancedSearchQuery().orEmpty()
        val parsed = ParsedSearch(query = query, fallbackKinds = fallbackKinds)
        val authors = parsed.authors.ifEmpty {
            if (parsed.scope == "myfollows") loadFollowAuthors(userId) else emptyList()
        }
        val taggedPubkeys = if (parsed.scope == "mynotifications") listOf(userId) else null
        val filter = RelayFilter(
            kinds = parsed.kinds,
            authors = authors.ifEmpty { null },
            pubkeyTags = parsed.pubkeyTags ?: taggedPubkeys,
            eventTags = parsed.eventTags,
            search = parsed.searchTerms.ifEmpty { null },
            limit = limit,
            until = until ?: parsed.until,
            since = since ?: parsed.since,
        )
        val events = query(filter).let { firstPage ->
            // NIP-50 is optional. If a relay ignores or rejects `search`, retry
            // the same standard filters and apply the text predicate locally.
            // Do not keep the UI page size here: a 20-event arbitrary sample
            // makes rare terms look as if they only have ancient results.
            if (firstPage.isEmpty() && filter.search != null) {
                query(
                    filter.copy(
                        search = null,
                        limit = maxOf(filter.limit ?: 0, FALLBACK_SEARCH_EVENT_LIMIT),
                    ),
                )
            } else {
                firstPage
            }
        }

        val page = events.asSequence()
            .distinctBy { it.id }
            .filter { it.matches(parsed.positiveTerms, parsed.negativeTerms) }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
        Napier.i {
            "Advanced relay search query='$query' events=${events.size} results=${page.size} " +
                "newest=${page.maxOfOrNull { it.createdAt }} oldest=${page.minOfOrNull { it.createdAt }}"
        }
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
        return page.toFeedResponse(metadata)
    }

    private suspend fun query(filter: RelayFilter): List<NostrEvent> = runCatching {
        querier.query(filter)
    }.getOrDefault(emptyList())

    private suspend fun loadFollowAuthors(userId: String): List<String> = runCatching {
        // Routed through the coordinator: the note feed, article feed and profile screen already
        // ask for this same follow list, often within the same burst of tab loads at app start.
        coordinator.fetchFollowList(querier = querier, pubkey = userId)
            .maxByOrNull { it.createdAt }
            ?.tags
            ?.mapNotNull { tag ->
                if (tag.size >= 2 && tag.getOrNull(0)?.jsonPrimitive?.contentOrNull == "p") {
                    tag.getOrNull(1)?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun NostrEvent.matches(positiveTerms: List<String>, negativeTerms: List<String>): Boolean {
        val content = content.lowercase()
        return positiveTerms.all { content.contains(it.lowercase()) } &&
            negativeTerms.none { content.contains(it.lowercase()) }
    }

    private class ParsedSearch(query: String, fallbackKinds: List<Int>) {
        private val tokens = TOKEN.findAll(query).toList()
        val kinds = tokens.mapNotNull { it.groupValues[2].toIntOrNull() }
            .ifEmpty { fallbackKinds }
            .distinct()
        val authors = values("from").mapNotNull { it.toPubkeyOrNull() }.distinct()
        val pubkeyTags = values("zappedby").mapNotNull { it.toPubkeyOrNull() }.ifEmpty { null }
        val eventTags = values("to").filter { it.length == 64 }.distinct().ifEmpty { null }
        val scope = values("scope").firstOrNull().orEmpty()
        val since = values("since").firstOrNull()?.toRelayTimestamp()
        val until = values("until").firstOrNull()?.toRelayTimestamp()
        val searchTerms = query
            .replace(CONTROL, " ")
            .replace(Regex("\\bOR\\b", RegexOption.IGNORE_CASE), " ")
            .replace("(", " ")
            .replace(")", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.equals("pas:1", ignoreCase = true) }
            .joinToString(" ")
        val positiveTerms = searchTerms.split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.startsWith("-") }
        val negativeTerms = searchTerms.split(Regex("\\s+"))
            .filter { it.startsWith("-") && it.length > 1 }
            .map { it.removePrefix("-") }

        private fun values(name: String): List<String> = tokens
            .filter { it.groupValues[1].equals(name, ignoreCase = true) }
            .map { it.groupValues[2] }

        private fun String.toPubkeyOrNull(): String? = runCatching { assureValidPubKeyHex() }.getOrNull()

        private fun String.toRelayTimestamp(): Long? = when (lowercase()) {
            "yesterday" -> Clock.System.now().minus(1.days).epochSeconds
            "lastweek" -> Clock.System.now().minus(7.days).epochSeconds
            "lastmonth" -> Clock.System.now().minus(30.days).epochSeconds
            "lastyear" -> Clock.System.now().minus(365.days).epochSeconds
            else -> toLongOrNull()
        }

        companion object {
            private val TOKEN = Regex("(?:^|[\\s(])((?:kind|from|to|zappedby|scope|since|until)):(\\d+|[^\\s()]+)", RegexOption.IGNORE_CASE)
            private val CONTROL = Regex("(?:^|[\\s(])(?:kind|filter|from|to|zappedby|scope|since|until|orderby|orientation|minwords|maxwords|minduration|maxduration|minscore|mininteractions|minlikes|minzaps|minreplies|minreposts|repliestokind|pas):[^\\s()]+", RegexOption.IGNORE_CASE)
        }
    }

    private companion object {
        private const val FALLBACK_SEARCH_EVENT_LIMIT = 500
    }
}
