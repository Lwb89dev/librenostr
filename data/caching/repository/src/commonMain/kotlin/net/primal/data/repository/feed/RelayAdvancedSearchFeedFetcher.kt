package net.primal.data.repository.feed

import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.feeds.extractAdvancedSearchQuery
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.assureValidPubKeyHex
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

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
            hashtagTags = parsed.hashtags.ifEmpty { null },
            search = parsed.searchTerms.ifEmpty { null },
            limit = limit,
            until = until ?: parsed.until,
            since = since ?: parsed.since,
        )
        // NIP-50 is optional. If a relay ignores or rejects `search`, retry the same standard
        // filters and apply the text predicate locally. The decision is made on what survives
        // that predicate, not on the raw answer: a relay that ignores `search` returns arbitrary
        // events, and trusting that non-empty page skipped the retry only for the predicate to
        // throw all of it away, leaving the search with no results at all. The retry does not keep
        // the UI page size: a 20-event arbitrary sample makes rare terms look as if they only
        // have ancient results.
        val firstMatches = query(filter).matchingSearch(parsed)
        val events = if (firstMatches.isEmpty() && filter.search != null) {
            query(
                filter.copy(
                    search = null,
                    limit = maxOf(filter.limit ?: 0, FALLBACK_SEARCH_EVENT_LIMIT),
                ),
            ).matchingSearch(parsed)
        } else {
            firstMatches
        }

        val page = events.sortedByDescending { it.createdAt }.take(limit)
        Napier.i {
            "Advanced relay search query='$query' matches=${events.size} results=${page.size} " +
                "newest=${page.maxOfOrNull { it.createdAt }} oldest=${page.minOfOrNull { it.createdAt }}"
        }

        // Quoted notes (a `q` tag, or a bare `nostr:note1…`/`nevent1…` in the content) name a
        // specific note the content renderer needs — without this, a quote of anything not
        // already in the page for some other reason showed "Mentioned event not found," which
        // only ever affected search results since the feed/thread fetchers already do this.
        //
        // The page's own profiles are fetched at the same time as the quoted notes instead of
        // after them; only the (usually few) extra authors those quotes bring in wait for them.
        val pageIds = page.map { it.id }.toSet()
        val pagePubkeys = page.metadataSubjectPubkeys()
        val (referencedNotes, metadata) = coroutineScope {
            val referencedDeferred = async { queryByIds(page.referencedNoteIds().filterNot { it in pageIds }) }
            val pageMetadataDeferred = async { queryMetadata(pagePubkeys) }
            val referenced = referencedDeferred.await()
            val extraPubkeys = referenced.metadataSubjectPubkeys().filterNot { it in pagePubkeys }
            referenced to (pageMetadataDeferred.await() + queryMetadata(extraPubkeys))
        }
        return page.toFeedResponse(metadata, referencedEvents = referencedNotes.map { it.asReferencedPrimalEvent() })
    }

    private fun List<NostrEvent>.metadataSubjectPubkeys(): List<String> =
        (map { it.pubKey } + flatMap { it.tags.pubkeyTagValues() }).distinct()

    private suspend fun queryMetadata(pubkeys: List<String>): List<NostrEvent> {
        if (pubkeys.isEmpty()) return emptyList()
        return runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = pubkeys,
                    limit = pubkeys.size,
                ),
            ).latestMetadataByPubkey()
        }.getOrDefault(emptyList())
    }

    private fun List<NostrEvent>.matchingSearch(parsed: ParsedSearch): List<NostrEvent> =
        asSequence()
            .distinctBy { it.id }
            .filter { it.matches(parsed) }
            .toList()

    private suspend fun query(filter: RelayFilter): List<NostrEvent> =
        runCatching {
            querier.query(filter)
        }.getOrDefault(emptyList())

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

    private suspend fun loadFollowAuthors(userId: String): List<String> =
        runCatching {
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

    private fun NostrEvent.matches(parsed: ParsedSearch): Boolean {
        val content = content.lowercase()
        val positive = parsed.positiveTerms
        val positiveOk = when {
            positive.isEmpty() -> true
            // "bitcoin OR lightning" used to require both, the opposite of what was asked.
            parsed.anyTerm -> positive.any { content.contains(it) }
            else -> positive.all { content.contains(it) }
        }
        return positiveOk &&
            parsed.negativeTerms.none { content.contains(it) } &&
            parsed.mediaFilter?.let { it.containsMatchIn(content) } != false
    }

    private class ParsedSearch(query: String, fallbackKinds: List<Int>) {
        private val tokens = TOKEN.findAll(query).toList()

        // Only `kind:` tokens: every numeric token value used to count, so a `since:`/`until:`
        // timestamp that fit in an Int silently turned into a kind filter nothing matched.
        val kinds = values("kind").mapNotNull { it.toIntOrNull() }
            .ifEmpty { fallbackKinds }
            .distinct()
        val authors = values("from").mapNotNull { it.toPubkeyOrNull() }.distinct()
        val pubkeyTags = values("zappedby").mapNotNull { it.toPubkeyOrNull() }.ifEmpty { null }
        val eventTags = values("to").filter { it.length == 64 }.distinct().ifEmpty { null }
        val scope = values("scope").firstOrNull().orEmpty()
        val since = values("since").firstOrNull()?.toRelayTimestamp()
        val until = values("until").firstOrNull()?.toRelayTimestamp()

        // Routed to a NIP-12 `#t` tag filter, not NIP-50 full-text search: a relay honors `limit`
        // on a tag filter by returning the newest matches (NIP-01), but a search term is only
        // required to return its best full-text matches, which skews toward old, heavily-discussed
        // posts over recent ones — see RelayFilter.hashtagTags' own doc.
        //
        // Only ever a single hashtag: a relay's `#t` filter matches ANY of its values (NIP-01),
        // so two-plus hashtags here would silently loosen "both tags" to "either tag." With more
        // than one, fall back entirely to the full-text search below, which already ANDs them via
        // positiveTerms — the same thing this fetcher did before hashtags got their own routing.
        private val allHashtags = HASHTAG.findAll(query).map { it.groupValues[1].lowercase() }.distinct().toList()
        val hashtags = if (allHashtags.size == 1) allHashtags else emptyList()

        /** "a OR b" matches either term instead of requiring both. */
        val anyTerm = OR_OPERATOR.containsMatchIn(query)

        /** `filter:image|video|audio` — stripped from the text search, applied to the content. */
        val mediaFilter: Regex? = MEDIA_FILTER.find(query)?.groupValues?.get(1)?.lowercase()?.let { MEDIA_URL[it] }

        val searchTerms = query
            .let { if (hashtags.isNotEmpty()) it.replace(HASHTAG, " ") else it }
            .replace(CONTROL, " ")
            .replace(OR_OPERATOR, " ")
            .replace("(", " ")
            .replace(")", " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() && !it.equals("pas:1", ignoreCase = true) }
            .joinToString(" ")
        private val terms = searchTerms.split(WHITESPACE).filter { it.isNotBlank() }.map { it.lowercase() }
        val positiveTerms = terms.filterNot { it.startsWith("-") }
        val negativeTerms = terms.filter { it.startsWith("-") && it.length > 1 }.map { it.removePrefix("-") }

        private fun values(name: String): List<String> =
            tokens
                .filter { it.groupValues[1].equals(name, ignoreCase = true) }
                .map { it.groupValues[2] }

        private fun String.toPubkeyOrNull(): String? = runCatching { assureValidPubKeyHex() }.getOrNull()

        private fun String.toRelayTimestamp(): Long? =
            when (lowercase()) {
                "yesterday" -> Clock.System.now().minus(1.days).epochSeconds
                "lastweek" -> Clock.System.now().minus(7.days).epochSeconds
                "lastmonth" -> Clock.System.now().minus(30.days).epochSeconds
                "lastyear" -> Clock.System.now().minus(365.days).epochSeconds
                else -> toLongOrNull() ?: isoDateToEpochSeconds()
            }

        /**
         * `yyyy-MM-dd` (the advanced search screen's date picker format) as UTC midnight. It used
         * to fall through `toLongOrNull()` and be dropped, so a custom date range did nothing.
         */
        // The numbers are the calendar algorithm's own constants (days per 400-year era, month
        // offsets, ...); naming each one would only obscure a well-known published formula.
        @Suppress("MagicNumber", "ReturnCount")
        private fun String.isoDateToEpochSeconds(): Long? {
            val match = ISO_DATE.matchEntire(this) ?: return null
            val (y, m, d) = match.destructured.toList().map { it.toInt() }
            if (m !in 1..12 || d !in 1..31) return null
            // Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
            val year = if (m <= 2) y - 1 else y
            val era = (if (year >= 0) year else year - 399) / 400
            val yearOfEra = year - era * 400
            val dayOfYear = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            val days = era.toLong() * 146_097 + dayOfEra - 719_468
            return days * 86_400
        }

        companion object {
            private val TOKEN =
                Regex(
                    "(?:^|[\\s(])((?:kind|from|to|zappedby|scope|since|until)):(\\d+|[^\\s()]+)",
                    RegexOption.IGNORE_CASE,
                )
            private val CONTROL =
                Regex(
                    "(?:^|[\\s(])(?:kind|filter|from|to|zappedby|scope|since|until|orderby|orientation|" +
                        "minwords|maxwords|minduration|maxduration|minscore|mininteractions|minlikes|minzaps|" +
                        "minreplies|minreposts|repliestokind|pas):[^\\s()]+",
                    RegexOption.IGNORE_CASE,
                )
            private val HASHTAG = Regex("(?:^|[\\s(])#([^\\s()]+)")
            private val OR_OPERATOR = Regex("\\bOR\\b", RegexOption.IGNORE_CASE)
            private val WHITESPACE = Regex("\\s+")
            private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
            private val MEDIA_FILTER = Regex("(?:^|[\\s(])filter:(image|video|audio)\\b", RegexOption.IGNORE_CASE)
            private val MEDIA_URL = mapOf(
                "image" to Regex("https?://\\S+\\.(?:jpe?g|png|gif|webp|avif)(?:\\?\\S*)?"),
                "video" to Regex("https?://\\S+\\.(?:mp4|mov|webm|m3u8)(?:\\?\\S*)?"),
                "audio" to Regex("https?://\\S+\\.(?:mp3|ogg|wav|m4a|flac)(?:\\?\\S*)?"),
            )
        }
    }

    private companion object {
        private const val FALLBACK_SEARCH_EVENT_LIMIT = 500
        private const val ID_CHUNK = 50
    }
}
