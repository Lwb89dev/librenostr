package net.primal.data.repository.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.common.ContentPrimalPaging
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.feeds.extractFollowSetDTag
import net.primal.domain.feeds.extractFollowSetPubkey
import net.primal.domain.feeds.extractPubkeyFromFeedSpec
import net.primal.domain.feeds.isFollowSetFeedSpec
import net.primal.domain.feeds.isNotesBookmarkFeedSpec
import net.primal.domain.feeds.isProfileAuthoredNoteRepliesFeedSpec
import net.primal.domain.feeds.isProfileAuthoredNotesFeedSpec
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.hasEventIdTag
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

internal class RelayNotesFeedFetcher(
    private val querier: RelayEventQuerier,
    private val coordinator: FetchCoordinator,
    private val cache: LocalEventCache? = null,
    /**
     * The notes the user bookmarked, most recently bookmarked first, as the local table holds them.
     * That table is kept current by the bookmark sync and by every add and remove, so it answers
     * without a round trip; when it is empty (a fresh install whose first sync has not landed
     * yet) the list is read from the relays instead.
     */
    private val localBookmarkedNoteIds: (suspend (userId: String, limit: Int) -> List<String>)? = null,
) {

    suspend fun fetch(
        userId: String,
        feedSpec: String,
        includeReplies: Boolean,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
    ): FeedResponse =
        if (feedSpec.isNotesBookmarkFeedSpec()) {
            fetchBookmarkedNotes(userId = userId, limit = limit, until = until, since = since)
        } else {
            fetchAuthoredNotes(
                userId = userId,
                feedSpec = feedSpec,
                includeReplies = includeReplies,
                limit = limit,
                until = until,
                since = since,
            )
        }

    /** Every feed whose scope is a set of authors: following, a follow set, or one profile. */
    private suspend fun fetchAuthoredNotes(
        userId: String,
        feedSpec: String,
        includeReplies: Boolean,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
    ): FeedResponse {
        val authors = loadAuthors(userId, feedSpec)
        if (authors.isEmpty()) return emptyFeedResponse()

        val noteEvents = queryInChunks(
            authors = authors,
            kinds = listOf(
                NostrEventKind.ShortTextNote.value,
                NostrEventKind.ShortTextNoteRepost.value,
            ),
            limit = limit,
            until = until,
            since = since,
        )

        val unique = noteEvents.distinctBy { it.id }.sortedByDescending { it.createdAt }
        val notes = unique.filter { it.kind == NostrEventKind.ShortTextNote.value }
            .filter { includeReplies || !it.tags.hasEventIdTag() }
        val reposts = unique.filter { it.kind == NostrEventKind.ShortTextNoteRepost.value }
        val page = (notes + reposts).sortedByDescending { it.createdAt }.take(limit)
        return buildPageResponse(page)
    }

    /**
     * Everything a page of notes needs around it: the notes it quotes, and a kind 0 for its
     * authors and mentions. Shared by every feed this fetcher serves, whatever picked the page.
     */
    private suspend fun buildPageResponse(page: List<NostrEvent>): FeedResponse {
        val pageIds = page.map { it.id }.toSet()
        val pageAuthorCandidates = page.metadataAuthorCandidates()

        // Quoted notes (a `q` tag, or a bare `nostr:note1…`/`nevent1…` in the content) and the
        // page's own authors/mentions both depend only on `page`, not on each other, so both
        // round trips run at once instead of the mention metadata waiting behind the quotes —
        // the same pattern RelayNotificationsFetcher already uses for the same reason.
        val (referencedNotes, pageMetadata) = coroutineScope {
            val referenced = async {
                fetchReferencedNotes(referencedIds = page.referencedNoteIds().filterNot { it in pageIds })
            }
            val metadata = async { fetchMetadataFor(pubkeys = pageAuthorCandidates) }
            referenced.await() to metadata.await()
        }

        // A quoted note can introduce an author the page itself never mentioned. Everyone else
        // was already covered by the query above, so this is a small, often-empty top-up rather
        // than a third full round trip.
        val extraAuthorCandidates = referencedNotes.metadataAuthorCandidates() - pageAuthorCandidates.toSet()
        val extraMetadata = fetchMetadataFor(pubkeys = extraAuthorCandidates)

        return page.toFeedResponse(
            metadata = pageMetadata + extraMetadata,
            referencedEvents = referencedNotes.map { it.asReferencedPrimalEvent() },
        )
    }

    /**
     * A bookmarks feed is the odd one out among the feeds served here: its scope is not a set of
     * authors but the list of event ids the user saved (NIP-51, kind 10003), so there is no author
     * filter to build. Each id is resolved the way a quoted note is, from what is already stored
     * locally and from the relays only for the rest, and the page is then cut by the notes' own
     * timestamps like every other feed, so the mediator's `until` cursor keeps its usual meaning.
     *
     * Only kind 1 notes come back: they are the one kind the relay-served feeds turn into feed
     * posts with their content intact. A bookmark that points at anything else is left out rather
     * than shown as an empty card.
     */
    private suspend fun fetchBookmarkedNotes(
        userId: String,
        limit: Int,
        until: Long?,
        since: Long?,
    ): FeedResponse {
        val bookmarkedIds = loadBookmarkedNoteIds(userId)
        if (bookmarkedIds.isEmpty()) return emptyFeedResponse()

        val page = fetchReferencedNotes(referencedIds = bookmarkedIds)
            .distinctBy { it.id }
            .filter { it.kind == NostrEventKind.ShortTextNote.value }
            .filter { until == null || it.createdAt <= until }
            .filter { since == null || it.createdAt >= since }
            .sortedByDescending { it.createdAt }
            .take(limit)
        return buildPageResponse(page)
    }

    private suspend fun loadBookmarkedNoteIds(userId: String): List<String> {
        val local = runCatching { localBookmarkedNoteIds?.invoke(userId, MAX_BOOKMARKED_NOTES) }
            .getOrDefault(null)
            .orEmpty()
        return local.ifEmpty { loadBookmarkedNoteIdsFromRelays(userId) }
    }

    private suspend fun loadBookmarkedNoteIdsFromRelays(userId: String): List<String> {
        val list = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.BookmarksList.value),
                    authors = listOf(userId),
                    limit = BOOKMARK_LIST_QUERY_LIMIT,
                ),
            )
        }.getOrDefault(emptyList()).maxByOrNull { it.createdAt } ?: return emptyList()

        // Entries are appended as they are added, so the newest bookmark is the last tag.
        return list.tags.eventIdTagValues().distinct().asReversed().take(MAX_BOOKMARKED_NOTES)
    }

    /** Everyone the content renderer might need a kind 0 for: an author, or an `nostr:` mention. */
    private fun List<NostrEvent>.metadataAuthorCandidates(): List<String> =
        (map { it.pubKey } + flatMap { it.tags.pubkeyTagValues() }).distinct()

    /**
     * The notes a page's own quotes/mentions point at are usually already stored locally,
     * because either the feed or a previous page put them there. [cache] answers that without a
     * relay round trip; only ids it doesn't recognize are actually requested.
     */
    private suspend fun fetchReferencedNotes(referencedIds: List<String>): List<NostrEvent> {
        if (referencedIds.isEmpty()) return emptyList()
        val cached = cache?.partitionKnownEventIds(referencedIds)
        val missing = cached?.missing ?: referencedIds
        val known = cached?.known.orEmpty()
        return if (missing.isEmpty()) known else known + queryByIds(missing)
    }

    /**
     * Without the mentioned profiles the renderer has no kind 0 to resolve a `nostr:` mention
     * against and falls back to an ellipsized npub, so a tagged user showed up as @npub1abc…xyz
     * instead of their name. [cache] claims each pubkey for the session so a profile already
     * fetched by an earlier page (an active poster recurs across pages far more often than not)
     * is never asked for twice; a claim that comes back empty-handed is released so it can be
     * retried later instead of staying a raw npub for the rest of the session.
     */
    private suspend fun fetchMetadataFor(pubkeys: List<String>): List<NostrEvent> {
        val distinct = pubkeys.distinct().take(MAX_METADATA_AUTHORS)
        val wanted = cache?.claimMetadataPubkeys(distinct) ?: distinct
        if (wanted.isEmpty()) return emptyList()
        val metadata = queryInChunks(
            authors = wanted,
            kinds = listOf(NostrEventKind.Metadata.value),
            limit = wanted.size,
        )
        cache?.releaseMetadataPubkeys(wanted - metadata.map { it.pubKey }.toSet())
        return metadata
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

    /** The author scope for a feed spec. Exposed so a live subscription can reuse it. */
    suspend fun resolveAuthors(userId: String, feedSpec: String): List<String> = loadAuthors(userId, feedSpec)

    private suspend fun loadAuthors(userId: String, feedSpec: String): List<String> {
        if (feedSpec.isFollowSetFeedSpec()) {
            val pubkey = feedSpec.extractFollowSetPubkey() ?: userId
            val dTag = feedSpec.extractFollowSetDTag() ?: return emptyList()
            return loadFollowSet(pubkey, dTag)
        }
        feedSpec.profileFeedAuthor()?.let { return listOf(it) }
        return loadFollows(userId) + userId
    }

    /**
     * The one person a profile tab is about.
     *
     * Somebody's notes are a plain `authors` filter, so there was never a reason for these tabs to
     * need anything a relay cannot do. They fell through to the centralized feed API instead,
     * which in a relay-only build always throws, so the notes and replies tabs of every profile
     * showed "unable to load content" and always had.
     */
    private fun String.profileFeedAuthor(): String? =
        when {
            isProfileAuthoredNotesFeedSpec() ->
                extractPubkeyFromFeedSpec(prefix = AUTHORED_NOTES_PREFIX, suffix = "}")

            isProfileAuthoredNoteRepliesFeedSpec() ->
                extractPubkeyFromFeedSpec(prefix = AUTHORED_REPLIES_PREFIX, suffix = "}")

            else -> null
        }

    private suspend fun loadFollowSet(pubkey: String, dTag: String): List<String> {
        val events = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.CategorizedPeopleList.value),
                    authors = listOf(pubkey),
                ),
            )
        }.getOrDefault(emptyList())
        val latest = events
            .filter { it.tags.findFirstIdentifier() == dTag }
            .maxByOrNull { it.createdAt }
            ?: return emptyList()
        return latest.tags.pubkeyTagValues().distinct().take(MAX_FOLLOW_AUTHORS)
    }

    private suspend fun queryInChunks(
        authors: List<String>,
        kinds: List<Int>,
        limit: Int? = null,
        until: Long? = null,
        since: Long? = null,
    ): List<NostrEvent> {
        if (authors.isEmpty()) return emptyList()
        val capped = authors.take(MAX_FOLLOW_AUTHORS)
        val chunks = capped.chunked(AUTHOR_CHUNK)
        val semaphore = Semaphore(MAX_PARALLEL_CHUNKS)
        return coroutineScope {
            chunks.map { chunk ->
                async {
                    queryChunkLimited(semaphore, chunk, kinds, limit, until, since)
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun queryChunkLimited(
        semaphore: Semaphore,
        chunk: List<String>,
        kinds: List<Int>,
        limit: Int?,
        until: Long?,
        since: Long?,
    ): List<NostrEvent> =
        semaphore.withPermit {
            queryChunk(
                authors = chunk,
                kinds = kinds,
                limit = limit ?: chunk.size,
                until = until,
                since = since,
            )
        }

    private suspend fun queryChunk(
        authors: List<String>,
        kinds: List<Int>,
        limit: Int,
        until: Long?,
        since: Long?,
    ): List<NostrEvent> =
        runCatching {
            querier.query(
                RelayFilter(
                    kinds = kinds,
                    authors = authors,
                    limit = limit,
                    until = until,
                    since = since,
                ),
            )
        }.getOrDefault(emptyList())

    /**
     * The follow list used to be fetched before every single page — a full round trip paid up
     * front, with the notes query blocked behind it — and the per-fetcher cache that replaced it
     * lived as long as the object, which meant the article feed and the profile screen each kept
     * their own copy. The coordinator holds one, briefly, for all of them.
     */
    private suspend fun loadFollows(userId: String): List<String> {
        val events = runCatching { coordinator.fetchFollowList(querier = querier, pubkey = userId) }
            .getOrDefault(emptyList())
        val latest = events.maxByOrNull { it.createdAt } ?: return emptyList()
        return latest.tags.pubkeyTagValues().distinct().take(MAX_FOLLOW_AUTHORS)
    }

    companion object {
        /** Relays reject very large filter arrays; a page of notes cannot need more. */
        private const val MAX_METADATA_AUTHORS = 500

        // Wider chunks and more of them in flight mean fewer sequential waves. A page used to
        // cost ceil(follows / 100) / 4 waves, and each wave is bounded by the relay timeout, so
        // 800 follows paid two full waves before a single note could be shown.
        private const val AUTHOR_CHUNK = 250
        private const val MAX_FOLLOW_AUTHORS = 2_000
        private const val MAX_PARALLEL_CHUNKS = 8
        private const val ID_CHUNK = 50

        /** Every page resolves the whole list, so the list a feed will ever look at is bounded. */
        private const val MAX_BOOKMARKED_NOTES = 300
        private const val BOOKMARK_LIST_QUERY_LIMIT = 5

        private const val AUTHORED_NOTES_PREFIX = """{"id":"feed","kind":"notes","notes":"authored""""
        private const val AUTHORED_REPLIES_PREFIX =
            """{"id":"feed","include_replies":true,"kind":"notes","notes":"authored""""
    }
}

internal fun List<NostrEvent>.toFeedResponse(
    metadata: List<NostrEvent>,
    referencedEvents: List<PrimalEvent> = emptyList(),
): FeedResponse {
    val notes = filter { it.kind == NostrEventKind.ShortTextNote.value }
    val reposts = filter { it.kind == NostrEventKind.ShortTextNoteRepost.value }
    val created = map { it.createdAt }
    val paging = if (created.isEmpty()) {
        ContentPrimalPaging(orderBy = "created_at", sinceId = 0, untilId = 0, elements = emptyList())
    } else {
        ContentPrimalPaging(
            orderBy = "created_at",
            sinceId = created.min(),
            untilId = created.max(),
            elements = map { it.id },
        )
    }
    return FeedResponse(
        paging = paging,
        metadata = metadata,
        notes = notes,
        articles = filter { it.kind == NostrEventKind.LongFormContent.value },
        reposts = reposts,
        zaps = emptyList(),
        referencedEvents = referencedEvents,
        primalEventStats = emptyList(),
        primalEventUserStats = emptyList(),
        cdnResources = emptyList(),
        primalLinkPreviews = emptyList(),
        primalRelayHints = emptyList(),
        blossomServers = emptyList(),
    )
}

internal fun emptyFeedResponse(): FeedResponse = emptyList<NostrEvent>().toFeedResponse(emptyList())
