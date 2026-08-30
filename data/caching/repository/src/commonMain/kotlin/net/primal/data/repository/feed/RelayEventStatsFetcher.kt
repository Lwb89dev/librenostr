package net.primal.data.repository.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.dao.events.EventUserStats
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstZapAmount
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

internal data class RelayEventStatsSnapshot(
    val eventStats: List<EventStats>,
    val userStats: List<EventUserStats>,
)

/** Derives note counters from the interaction events published on relays. */
internal class RelayEventStatsFetcher(
    private val querier: RelayEventQuerier,
) {

    suspend fun fetch(eventIds: List<String>, userId: String): RelayEventStatsSnapshot {
        val ids = eventIds.distinct()
        if (ids.isEmpty()) return RelayEventStatsSnapshot(emptyList(), emptyList())

        val interactions = coroutineScope {
            INTERACTION_KINDS.flatMap { kind ->
                ids.chunked(EVENT_TAG_CHUNK).map { chunk ->
                    async {
                        runCatching {
                            querier.query(
                                RelayFilter(
                                    kinds = listOf(kind),
                                    eventTags = chunk,
                                    limit = MAX_EVENTS_PER_QUERY,
                                ),
                            )
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }

        val eventsByTarget = interactions
            .flatMap { event ->
                event.tags.eventIdTagValues()
                    .filter { it in ids }
                    .distinct()
                    .map { it to event }
            }
            .groupBy({ it.first }, { it.second })

        val eventStats = ids.map { eventId ->
            val events = eventsByTarget[eventId].orEmpty()
            EventStats(
                eventId = eventId,
                likes = events.count { it.kind == NostrEventKind.Reaction.value }.toLong(),
                replies = events.count { it.kind == NostrEventKind.ShortTextNote.value }.toLong(),
                reposts = events.count { it.kind == NostrEventKind.ShortTextNoteRepost.value }.toLong(),
                zaps = events.count { it.kind == NostrEventKind.Zap.value }.toLong(),
                satsZapped = events
                    .filter { it.kind == NostrEventKind.Zap.value }
                    .sumOf { it.tags.findFirstZapAmount()?.toLongOrNull()?.toSats() ?: 0L },
            )
        }

        val userStats = ids.mapNotNull { eventId ->
            val events = eventsByTarget[eventId].orEmpty().filter { it.pubKey == userId }
            if (events.isEmpty()) return@mapNotNull null
            EventUserStats(
                eventId = eventId,
                userId = userId,
                replied = events.any { it.kind == NostrEventKind.ShortTextNote.value },
                liked = events.any { it.kind == NostrEventKind.Reaction.value },
                reposted = events.any { it.kind == NostrEventKind.ShortTextNoteRepost.value },
                zapped = events.any { it.kind == NostrEventKind.Zap.value },
            )
        }
        return RelayEventStatsSnapshot(eventStats = eventStats, userStats = userStats)
    }

    private fun Long.toSats(): Long = if (this >= 1_000L) this / 1_000L else this

    private companion object {
        private const val EVENT_TAG_CHUNK = 50
        private const val MAX_EVENTS_PER_QUERY = 500
        private val INTERACTION_KINDS = listOf(
            NostrEventKind.Reaction.value,
            NostrEventKind.ShortTextNote.value,
            NostrEventKind.ShortTextNoteRepost.value,
            NostrEventKind.Zap.value,
        )
    }
}
