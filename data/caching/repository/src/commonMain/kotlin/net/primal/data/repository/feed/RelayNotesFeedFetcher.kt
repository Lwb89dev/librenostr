package net.primal.data.repository.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.domain.common.ContentPrimalPaging
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.feeds.extractFollowSetDTag
import net.primal.domain.feeds.extractFollowSetPubkey
import net.primal.domain.feeds.isFollowSetFeedSpec
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.hasEventIdTag
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

internal class RelayNotesFeedFetcher(
    private val querier: RelayEventQuerier,
) {

    suspend fun fetch(
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
        val metadataAuthors = page.map { it.pubKey }.distinct()
        val metadata = if (metadataAuthors.isEmpty()) {
            emptyList()
        } else {
            queryInChunks(
                authors = metadataAuthors,
                kinds = listOf(NostrEventKind.Metadata.value),
                limit = metadataAuthors.size,
            )
        }
        return page.toFeedResponse(metadata)
    }

    private suspend fun loadAuthors(userId: String, feedSpec: String): List<String> {
        if (feedSpec.isFollowSetFeedSpec()) {
            val pubkey = feedSpec.extractFollowSetPubkey() ?: userId
            val dTag = feedSpec.extractFollowSetDTag() ?: return emptyList()
            return loadFollowSet(pubkey, dTag)
        }
        return loadFollows(userId) + userId
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

    private suspend fun loadFollows(userId: String): List<String> {
        val events = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.FollowList.value),
                    authors = listOf(userId),
                    limit = 1,
                ),
            )
        }.getOrDefault(emptyList())
        val latest = events.maxByOrNull { it.createdAt } ?: return emptyList()
        return latest.tags.pubkeyTagValues().distinct().take(MAX_FOLLOW_AUTHORS)
    }

    companion object {
        private const val AUTHOR_CHUNK = 100
        private const val MAX_FOLLOW_AUTHORS = 2_000
        private const val MAX_PARALLEL_CHUNKS = 4
    }
}

internal fun List<NostrEvent>.toFeedResponse(metadata: List<NostrEvent>): FeedResponse {
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
        articles = emptyList(),
        reposts = reposts,
        zaps = emptyList(),
        referencedEvents = emptyList(),
        primalEventStats = emptyList(),
        primalEventUserStats = emptyList(),
        cdnResources = emptyList(),
        primalLinkPreviews = emptyList(),
        primalRelayHints = emptyList(),
        blossomServers = emptyList(),
    )
}

internal fun emptyFeedResponse(): FeedResponse = emptyList<NostrEvent>().toFeedResponse(emptyList())
