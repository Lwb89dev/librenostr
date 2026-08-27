package net.primal.data.repository.feed

import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findReplyTargetId
import net.primal.domain.nostr.findRootEventId
import net.primal.domain.nostr.isReply
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

internal class RelayThreadFetcher(
    private val querier: RelayEventQuerier,
) {

    suspend fun fetch(noteId: String, kinds: List<Int>, limit: Int): FeedResponse {
        val opened = queryByIds(listOf(noteId)).firstOrNull { it.id == noteId }
        val ancestors = walkAncestors(opened)
        val rootId = rootIdOf(opened = opened, ancestors = ancestors, noteId = noteId)
        val root = eventById(rootId, listOfNotNull(opened) + ancestors)
        val tagged = queryTagged(
            seedIds = listOf(rootId, noteId) + ancestors.map { it.id },
            kinds = kinds,
            limit = limit,
        )
        val known = (listOfNotNull(opened, root) + ancestors + tagged).distinctBy { it.id }
        val filled = queryByIds(missingParentIds(known))
        val all = (known + filled).distinctBy { it.id }
        val metadata = loadMetadata(all.map { it.pubKey }.distinct())
        return all.toThreadFeedResponse(metadata)
    }

    private suspend fun eventById(id: String, known: List<NostrEvent>): NostrEvent? =
        known.firstOrNull { it.id == id } ?: queryByIds(listOf(id)).firstOrNull { it.id == id }

    private suspend fun queryByIds(ids: List<String>): List<NostrEvent> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_CHUNK).flatMap { chunk ->
            runCatching {
                querier.query(RelayFilter(ids = chunk, limit = chunk.size))
            }.getOrDefault(emptyList())
        }
    }

    private suspend fun walkAncestors(start: NostrEvent?): List<NostrEvent> {
        val chain = mutableListOf<NostrEvent>()
        val seen = mutableSetOf<String>()
        var current = start
        var hops = 0
        while (current != null && hops < MAX_HOPS) {
            val parentId = current.tags.findReplyTargetId() ?: break
            if (!seen.add(parentId) || parentId == current.id) break
            val parent = queryByIds(listOf(parentId)).firstOrNull { it.id == parentId } ?: break
            chain += parent
            current = parent
            hops++
        }
        return chain
    }

    private fun rootIdOf(opened: NostrEvent?, ancestors: List<NostrEvent>, noteId: String): String {
        val oldest = ancestors.lastOrNull()
        if (oldest != null && !oldest.tags.isReply()) return oldest.id
        return opened?.tags?.findRootEventId()
            ?: opened?.tags?.findReplyTargetId()
            ?: opened?.id
            ?: noteId
    }

    private suspend fun queryTagged(seedIds: List<String>, kinds: List<Int>, limit: Int): List<NostrEvent> {
        val first = queryEventTags(seedIds.distinct(), kinds, limit)
        val extra = first.map { it.id }.filter { it !in seedIds }.distinct()
        val second = queryEventTags(extra, kinds, limit)
        return first + second
    }

    private suspend fun queryEventTags(eventTags: List<String>, kinds: List<Int>, limit: Int): List<NostrEvent> {
        if (eventTags.isEmpty()) return emptyList()
        return eventTags.chunked(ID_CHUNK).flatMap { chunk ->
            runCatching {
                querier.query(RelayFilter(kinds = kinds, eventTags = chunk, limit = limit))
            }.getOrDefault(emptyList())
        }
    }

    private fun missingParentIds(events: List<NostrEvent>): List<String> {
        val known = events.map { it.id }.toSet()
        return events.flatMap { it.tags.eventIdTagValues() }
            .filter { it !in known }
            .distinct()
            .take(MISSING_CAP)
    }

    private suspend fun loadMetadata(pubkeys: List<String>): List<NostrEvent> {
        if (pubkeys.isEmpty()) return emptyList()
        return pubkeys.chunked(AUTHOR_CHUNK).flatMap { chunk ->
            runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        authors = chunk,
                        limit = chunk.size,
                    ),
                )
            }.getOrDefault(emptyList())
        }.latestMetadataByPubkey()
    }

    companion object {
        private const val AUTHOR_CHUNK = 100
        private const val ID_CHUNK = 50
        private const val MAX_HOPS = 20
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
