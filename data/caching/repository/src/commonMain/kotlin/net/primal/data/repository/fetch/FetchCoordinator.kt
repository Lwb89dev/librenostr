package net.primal.data.repository.fetch

import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Decides whether a fetch has to be sent at all, and who is waiting for the answer.
 *
 * Every repository used to talk to the relays on its own. Nothing knew what anything else had
 * asked for, so the same question went out several times over: the active user's follow list is
 * requested independently by the note feed, the article feed, advanced search, explore and the
 * profile screen, and at app start those load together. Profile metadata is worse, because two
 * screens showing the same author each asked for kind 0 separately.
 *
 * This sits between the repositories and [RelayEventQuerier] and answers one question: is somebody
 * already asking this? If so the caller attaches to the request in flight instead of opening
 * another one. It is deliberately *not* a cache of events — that is Room's job, and the in-memory
 * hot layer in front of it already exists. What lives here is the set of requests currently in the
 * air and the consumers waiting on each.
 *
 * The querier is passed per call rather than held, so this stays a plain process-wide singleton
 * with no mutable wiring to race over. The registry is keyed by [FetchKey] alone, which assumes
 * the single transport the app actually has; a second one would need the key to say which.
 *
 * Two mechanisms that are easy to confuse, kept apart on purpose:
 *
 *  - **coalescing**, here: concurrent callers wanting the same thing share one request. Nobody
 *    ever sees staler data than they would have without it, so it is always safe.
 *  - **session claiming**, in the event cache: a pubkey is asked about once per session and never
 *    again. That trades freshness for traffic, which is right for a feed and wrong for the profile
 *    screen, so it stays opt-in and is not applied here.
 */
internal class FetchCoordinator(
    dispatcherProvider: DispatcherProvider,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val followListTtlSeconds: Long = FOLLOW_LIST_TTL_SECONDS,
) {

    private val scope = CoroutineScope(dispatcherProvider.io() + SupervisorJob())
    private val mutex = Mutex()

    /**
     * Requests somebody is still waiting on, and how many callers that is.
     *
     * Membership is governed by the consumer count alone: the entry goes when the last caller
     * leaves, which is what stops this from quietly turning into a cache with no expiry. A caller
     * that arrives in the moment between the answer landing and the last consumer letting go
     * shares that answer, which is the freshest data there is and one round trip saved.
     */
    private val inFlight = mutableMapOf<FetchKey, Operation>()

    private val followListCache = mutableMapOf<String, CachedFollowList>()

    private var stats = FetchStats()

    /**
     * Runs [filter] under [key], or attaches to the identical request already in flight.
     *
     * Failures propagate, matching what the call sites already do with a bare querier: they wrap
     * this in their own `runCatching` and decide what an empty answer means for them.
     */
    suspend fun fetch(
        querier: RelayEventQuerier,
        key: FetchKey,
        filter: RelayFilter,
    ): List<NostrEvent> {
        val operation = mutex.withLock { joinOrStart(key) { querier.query(filter) } }
        return operation.awaitAndRelease()
    }

    /**
     * Kind 0 for [pubkeys], coalesced per author rather than per call.
     *
     * Two screens asking for overlapping sets is the common case — the people in your feed are the
     * people who reply to you — so the unit of sharing has to be the author. Whatever this call
     * still has to ask for goes out as one batched query, not one query per author.
     */
    suspend fun fetchMetadata(querier: RelayEventQuerier, pubkeys: List<String>): List<NostrEvent> =
        coalesceByItem(
            items = pubkeys,
            keyOf = { FetchKey.ProfileMetadata(it) },
            belongsTo = { event, pubkey -> event.pubKey == pubkey },
            query = { authors -> queryMetadata(querier, authors) },
        )

    /**
     * Likes, replies, reposts and zaps pointing at [eventIds], coalesced per note.
     *
     * A note that turns up in more than one feed had its counters fetched once per feed, because
     * the fetcher is built per page and knows nothing of the others. The unit of sharing is the
     * note, so two feeds showing the same one ask about it once between them.
     *
     * Counters are the sort of thing that goes stale, which is why this coalesces and never
     * caches: sharing a request already in the air costs no freshness at all.
     */
    suspend fun fetchEventInteractions(querier: RelayEventQuerier, eventIds: List<String>): List<NostrEvent> =
        coalesceByItem(
            items = eventIds,
            keyOf = { FetchKey.EventInteractions(it) },
            belongsTo = { event, eventId -> eventId in event.tags.eventIdTagValues() },
            query = { ids -> queryInteractions(querier, ids) },
        )

    /**
     * Kind 3 for [pubkey], coalesced and briefly cached.
     *
     * Coalescing alone would not help here: the feeds that want this list do not always start
     * together, they start seconds apart as their tabs appear. The list changes rarely, so a short
     * time-to-live removes the repetition. It replaces per-fetcher fields that cached the same
     * list for the whole life of the object, which was a longer staleness window than this one.
     */
    suspend fun fetchFollowList(querier: RelayEventQuerier, pubkey: String): List<NostrEvent> {
        cachedFollowList(pubkey)?.let { return it }

        val events = fetch(
            querier = querier,
            key = FetchKey.FollowList(pubkey),
            filter = RelayFilter(
                kinds = listOf(NostrEventKind.FollowList.value),
                authors = listOf(pubkey),
                limit = FOLLOW_LIST_QUERY_LIMIT,
            ),
        )
        mutex.withLock {
            followListCache[pubkey] = CachedFollowList(events = events, fetchedAtSeconds = nowSeconds())
        }
        return events
    }

    /** What the coordinator has done so far. Counters only; no filters, no event content. */
    suspend fun stats(): FetchStats = mutex.withLock { stats }

    /**
     * Drops what was remembered for the current account.
     *
     * Requests still in flight are left alone. Their callers are waiting on an answer, and
     * nothing keyed here belongs to an account: a profile, a follow list and the reactions to a
     * note are public events, identical whoever asked for them.
     */
    suspend fun clearSession() =
        mutex.withLock {
            followListCache.clear()
            stats = FetchStats()
        }

    // ------------------------------------------------------------------ in-flight bookkeeping

    /** Call under [mutex]. */
    private fun joinOrStart(key: FetchKey, block: suspend () -> List<NostrEvent>): Operation {
        val existing = inFlight[key]
        if (existing != null) {
            existing.consumers += 1
            stats = stats.copy(coalescedRequests = stats.coalescedRequests + 1)
            return existing
        }

        val operation = Operation(key = key, deferred = scope.async { block() })
        inFlight[key] = operation
        countRelayQuery()
        return operation
    }

    /**
     * Shares per item rather than per call.
     *
     * Two callers rarely want exactly the same set — the feed wants the authors on its page, the
     * profile screen wants one of them — so keying on the whole request would almost never match.
     * Keying on each item does: whatever this call still has to ask for goes out as one batched
     * query, and the rest attaches to requests already in the air.
     */
    private suspend fun coalesceByItem(
        items: List<String>,
        keyOf: (String) -> FetchKey,
        belongsTo: (NostrEvent, String) -> Boolean,
        query: suspend (List<String>) -> List<NostrEvent>,
    ): List<NostrEvent> {
        val distinct = items.distinct().filter { it.isNotBlank() }
        if (distinct.isEmpty()) return emptyList()

        val operations = mutex.withLock { claimItems(distinct, keyOf, belongsTo, query) }
        return operations.awaitAllAndRelease().distinctBy { it.id }
    }

    /** Call under [mutex]. One batched query, one operation per item it covers. */
    private fun claimItems(
        items: List<String>,
        keyOf: (String) -> FetchKey,
        belongsTo: (NostrEvent, String) -> Boolean,
        query: suspend (List<String>) -> List<NostrEvent>,
    ): List<Operation> {
        val joined = mutableListOf<Operation>()
        val missing = mutableListOf<String>()

        items.forEach { item ->
            val existing = inFlight[keyOf(item)]
            when (existing) {
                null -> missing += item
                else -> {
                    existing.consumers += 1
                    stats = stats.copy(coalescedRequests = stats.coalescedRequests + 1)
                    joined += existing
                }
            }
        }

        if (missing.isNotEmpty()) joined += startBatch(missing, keyOf, belongsTo, query)
        return joined
    }

    /** Call under [mutex]. */
    private fun startBatch(
        items: List<String>,
        keyOf: (String) -> FetchKey,
        belongsTo: (NostrEvent, String) -> Boolean,
        query: suspend (List<String>) -> List<NostrEvent>,
    ): List<Operation> {
        val batch = scope.async { query(items) }
        countRelayQuery()

        // Abandoning every item of a batch cancels the per-item work but lets the batch itself
        // run to completion and be discarded. Untangling that would mean tracking a batch's items
        // to cancel it early, for at most one query already bounded by the relay timeout.
        return items.map { item ->
            // Failure is handled a level down, per chunk, so one unreachable relay costs its own
            // chunk and nothing else. By the time the batch resolves there is nothing left to
            // catch here, only the items it did come back with.
            val deferred = scope.async { batch.await().filter { belongsTo(it, item) } }
            val operation = Operation(key = keyOf(item), deferred = deferred)
            inFlight[operation.key] = operation
            operation
        }
    }

    private suspend fun queryMetadata(querier: RelayEventQuerier, pubkeys: List<String>): List<NostrEvent> =
        pubkeys.chunked(METADATA_AUTHOR_CHUNK).flatMap { chunk ->
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

    private suspend fun queryInteractions(querier: RelayEventQuerier, eventIds: List<String>): List<NostrEvent> =
        coroutineScope {
            // One request per kind: a single filter would let the busiest kind eat the whole limit
            // and truncate the rest, which is how the notification page used to lose events.
            INTERACTION_KINDS.flatMap { kind ->
                eventIds.chunked(EVENT_TAG_CHUNK).map { chunk ->
                    async {
                        runCatching {
                            querier.query(
                                RelayFilter(
                                    kinds = listOf(kind),
                                    eventTags = chunk,
                                    limit = MAX_INTERACTIONS_PER_QUERY,
                                ),
                            )
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }

    private suspend fun Operation.awaitAndRelease(): List<NostrEvent> {
        try {
            return deferred.await()
        } finally {
            withContext(NonCancellable) { release(this@awaitAndRelease) }
        }
    }

    private suspend fun List<Operation>.awaitAllAndRelease(): List<NostrEvent> {
        try {
            return map { it.deferred }.awaitAll().flatten()
        } finally {
            withContext(NonCancellable) { forEach { release(it) } }
        }
    }

    private suspend fun release(operation: Operation) =
        mutex.withLock {
            operation.consumers -= 1
            if (operation.consumers > 0) return@withLock

            inFlight.remove(operation.key, operation)
            if (!operation.deferred.isCompleted) {
                // Nobody is left to read the answer, so paying for the round trip buys nothing.
                operation.deferred.cancel()
                stats = stats.copy(abandonedQueries = stats.abandonedQueries + 1)
                Napier.d { "FetchCoordinator abandoned ${operation.key}" }
            }
        }

    /**
     * Call under [mutex].
     *
     * Summarised every so often rather than per request: the counters are only interesting as a
     * ratio, and a line per fetch would bury everything else in the log. Counts only — no filters,
     * no pubkeys, no event content — so this is safe to leave on.
     */
    private fun countRelayQuery() {
        stats = stats.copy(relayQueries = stats.relayQueries + 1)
        if (stats.relayQueries % STATS_LOG_INTERVAL != 0) return

        val current = stats
        Napier.i {
            "FetchCoordinator queries=${current.relayQueries} coalesced=${current.coalescedRequests} " +
                "cached=${current.servedFromCache} abandoned=${current.abandonedQueries}"
        }
    }

    private suspend fun cachedFollowList(pubkey: String): List<NostrEvent>? =
        mutex.withLock {
            val cached = followListCache[pubkey] ?: return@withLock null
            if (nowSeconds() - cached.fetchedAtSeconds >= followListTtlSeconds) return@withLock null
            stats = stats.copy(servedFromCache = stats.servedFromCache + 1)
            cached.events
        }

    private class Operation(
        val key: FetchKey,
        val deferred: Deferred<List<NostrEvent>>,
    ) {
        /** Guarded by the coordinator's mutex. */
        var consumers: Int = 1
    }

    private data class CachedFollowList(
        val events: List<NostrEvent>,
        val fetchedAtSeconds: Long,
    )

    companion object {
        /**
         * Long enough to cover the burst of feeds that open together at app start, short enough
         * that following somebody shows up in the feed without a restart.
         */
        const val FOLLOW_LIST_TTL_SECONDS = 300L

        /** How many relay queries between summary lines in the log. */
        private const val STATS_LOG_INTERVAL = 50

        /** Relays reject very large filter arrays. */
        private const val METADATA_AUTHOR_CHUNK = 50
        private const val EVENT_TAG_CHUNK = 50
        private const val MAX_INTERACTIONS_PER_QUERY = 500

        /** A follow list is replaceable; only the newest one means anything. */
        private const val FOLLOW_LIST_QUERY_LIMIT = 1

        private val INTERACTION_KINDS = listOf(
            NostrEventKind.Reaction.value,
            NostrEventKind.ShortTextNote.value,
            NostrEventKind.ShortTextNoteRepost.value,
            NostrEventKind.Zap.value,
        )
    }
}

/**
 * Counters for judging whether the coordinator is earning its place.
 *
 * Deliberately free of filters, pubkeys and event content, so this can be logged anywhere without
 * disclosing who the user reads or talks to.
 */
internal data class FetchStats(
    /** Requests actually sent to the relays. */
    val relayQueries: Int = 0,
    /** Requests that attached to something already in flight instead of opening another. */
    val coalescedRequests: Int = 0,
    /** Requests answered from the short-lived follow-list cache. */
    val servedFromCache: Int = 0,
    /** Requests cancelled because the last caller went away before the answer arrived. */
    val abandonedQueries: Int = 0,
)
