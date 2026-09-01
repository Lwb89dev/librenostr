package net.primal.data.repository.feed

import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findReplyTargetId
import net.primal.domain.nostr.findRootEventId
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Loads a whole thread around an opened note.
 *
 * Latency here is dominated by the number of sequential relay round trips, because each one is
 * bounded by its own EOSE grace and per-pool timeout. The previous version walked the ancestor
 * chain a hop at a time — one round trip per hop, up to five — and then made three or four more
 * in sequence, so opening a reply from the notification list could take tens of seconds.
 *
 * NIP-10 makes the walk unnecessary: a reply's `e` tags already name its root and its parent, so
 * the entire ancestor set can be requested in a single `ids` filter. What is left is three
 * rounds, and the second one runs the ancestors and the replies at the same time:
 *
 *  1. the opened note, and in parallel the replies pointing at it — this is what the user is
 *     looking at, so it is never queued behind anything else;
 *  2. the ancestors named by its tags, and the replies of the whole thread;
 *  3. profile metadata for everyone involved.
 */
internal class RelayThreadFetcher(
    private val querier: RelayEventQuerier,
    private val cache: LocalEventCache? = null,
) {

    suspend fun fetch(noteId: String, kinds: List<Int>, limit: Int): FeedResponse {
        // Round one. The opened note and its direct replies are what the screen renders first,
        // so they are asked for together rather than one behind the other.
        val (openedResult, directReplies) = coroutineScope {
            val opened = async { queryByIds(listOf(noteId)) }
            val replies = async { queryEventTags(listOf(noteId), kinds, limit) }
            listOf(opened, replies).awaitAll()
            opened.await() to replies.await()
        }
        val opened = openedResult.firstOrNull { it.id == noteId }

        // Every ancestor the opened note references, in one filter instead of one hop at a time.
        val ancestorIds = buildSet {
            opened?.tags?.findRootEventId()?.let { add(it) }
            opened?.tags?.findReplyTargetId()?.let { add(it) }
            opened?.tags?.eventIdTagValues()?.let { addAll(it) }
        }.filterNot { it == noteId }

        val rootId = opened?.tags?.findRootEventId()
            ?: opened?.tags?.findReplyTargetId()
            ?: noteId

        // Round two. Ancestors and the rest of the thread are independent of each other.
        val (ancestors, threadReplies) = coroutineScope {
            val ancestorsAsync = async { queryByIds(ancestorIds) }
            val threadAsync = async {
                if (rootId == noteId) emptyList() else queryEventTags(listOf(rootId), kinds, limit)
            }
            listOf(ancestorsAsync, threadAsync).awaitAll()
            ancestorsAsync.await() to threadAsync.await()
        }

        val known = (listOfNotNull(opened) + ancestors + directReplies + threadReplies)
            .distinctBy { it.id }

        // A single top-up pass for parents that were referenced but never arrived. Bounded, and
        // deliberately not recursive: a thread that is still incomplete after this renders with
        // what it has rather than paying more round trips.
        val filled = queryByIds(missingParentIds(known))
        val all = (known + filled).distinctBy { it.id }

        // Authors plus everyone the thread mentions, so a `nostr:` mention renders as the name
        // its owner chose rather than as an ellipsized npub.
        val metadata = loadMetadata(
            (all.map { it.pubKey } + all.flatMap { it.tags.pubkeyTagValues() }).distinct(),
        )

        Napier.d("Relay thread note=$noteId events=${all.size} ancestors=${ancestors.size}")
        return all.toThreadFeedResponse(metadata)
    }

    /**
     * Requests only the ids the database does not already hold. Nostr events are immutable and
     * content-addressed, so a stored id is the same event and re-requesting it buys nothing —
     * and a thread's ancestors are usually already there, put by the feed.
     */
    private suspend fun queryByIds(rawIds: List<String>): List<NostrEvent> {
        if (rawIds.isEmpty()) return emptyList()
        val cached = cache?.partitionKnownEventIds(rawIds)
        val ids = cached?.missing ?: rawIds
        val known = cached?.known.orEmpty()
        if (ids.isEmpty()) return known
        return known + coroutineScope {
            ids.chunked(ID_CHUNK).map { chunk ->
                async {
                    runCatching {
                        querier.query(RelayFilter(ids = chunk, limit = chunk.size))
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun queryEventTags(eventTags: List<String>, kinds: List<Int>, limit: Int): List<NostrEvent> {
        if (eventTags.isEmpty()) return emptyList()
        return coroutineScope {
            eventTags.chunked(ID_CHUNK).map { chunk ->
                async {
                    runCatching {
                        querier.query(RelayFilter(kinds = kinds, eventTags = chunk, limit = limit))
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    private fun missingParentIds(events: List<NostrEvent>): List<String> {
        val known = events.map { it.id }.toSet()
        return events.flatMap { it.tags.eventIdTagValues() }
            .filter { it !in known }
            .distinct()
            .take(MISSING_CAP)
    }

    private suspend fun loadMetadata(rawPubkeys: List<String>): List<NostrEvent> {
        if (rawPubkeys.isEmpty()) return emptyList()
        // Asked once per session rather than on every thread; profiles change, so this is not
        // cached permanently.
        val pubkeys = cache?.claimMetadataPubkeys(rawPubkeys) ?: rawPubkeys
        if (pubkeys.isEmpty()) return emptyList()
        val metadata = coroutineScope {
            pubkeys.chunked(AUTHOR_CHUNK).map { chunk ->
                async {
                    runCatching {
                        querier.query(
                            RelayFilter(
                                kinds = listOf(NostrEventKind.Metadata.value),
                                authors = chunk,
                                limit = chunk.size,
                            ),
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().latestMetadataByPubkey()
        }
        // A claim that is kept after coming back empty is how an author stays a raw npub for the
        // rest of the session: one timed-out request and nothing ever asks again.
        cache?.releaseMetadataPubkeys(pubkeys - metadata.map { it.pubKey }.toSet())
        return metadata
    }

    companion object {
        private const val AUTHOR_CHUNK = 100
        private const val ID_CHUNK = 50
        private const val MISSING_CAP = 50
    }
}

internal fun List<NostrEvent>.toThreadFeedResponse(metadata: List<NostrEvent>): FeedResponse {
    return FeedResponse(
        paging = null,
        metadata = metadata,
        notes = filter { it.kind == NostrEventKind.ShortTextNote.value },
        articles = emptyList(),
        reposts = filter { it.kind == NostrEventKind.ShortTextNoteRepost.value },
        zaps = filter { it.kind == NostrEventKind.Zap.value },
        referencedEvents = emptyList(),
        primalEventStats = emptyList(),
        primalEventUserStats = emptyList(),
        cdnResources = emptyList(),
        primalLinkPreviews = emptyList(),
        primalRelayHints = emptyList(),
        blossomServers = emptyList(),
        pictureNotes = filter { it.kind == NostrEventKind.PictureNote.value },
        polls = filter {
            it.kind == NostrEventKind.Poll.value || it.kind == NostrEventKind.ZapPoll.value
        },
        pollResponses = filter { it.kind == NostrEventKind.PollResponse.value },
    )
}
