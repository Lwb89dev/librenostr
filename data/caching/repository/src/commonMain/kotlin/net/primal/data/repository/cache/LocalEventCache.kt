package net.primal.data.repository.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.nostr.NostrEvent

/**
 * Keeps the relay fetchers from asking for things the app already holds.
 *
 * Three kinds of waste were being paid, in rising order of cost:
 *
 *  - **the same event decoded over and over.** A note that recurs across feed pages, a thread and
 *    a notification preview was read from SQLite and parsed from its raw JSON every single time.
 *    Measured on desktop at ~378us per lookup of 40 ids, of which ~208us is SQLite and ~89us is
 *    the JSON parse; on Android the database is encrypted, so the read costs more there.
 *  - **events requested by id.** A thread's ancestors, and the notes a notification refers to,
 *    are usually already in the database because the feed put them there. Nostr events are
 *    immutable and content-addressed, so a locally held id can be dropped from the filter with
 *    no staleness risk at all.
 *  - **profile metadata.** Every page re-requested kind 0 for the same authors. That is a relay
 *    round trip, not microseconds. Profiles do change, so this is deduplicated per session
 *    rather than permanently: a pubkey is asked for once while the app is running, which removes
 *    the repetition without freezing a display name until the cache is cleared.
 *
 * One instance is shared by everything the repository factory builds. Per-object caches looked
 * like they worked and did not: a paging mediator is constructed per Pager, so every notification
 * tab started with an empty dedupe set and asked the relays again for authors the feed had
 * already resolved.
 *
 * Deliberately not cached: anything whose value is the freshness itself — interaction counters,
 * zap receipts, notification pages.
 */
internal class LocalEventCache(
    private val database: CachingDatabase,
    private val maxHotEvents: Int = MAX_HOT_EVENTS,
) {

    private val mutex = Mutex()

    /**
     * Decoded events, most recently used last.
     *
     * Only ever filled from rows that were actually read out of the database, so an id reported
     * as known is an id the database holds. Filling it from relay responses would be faster still
     * and would let a failed write turn into an event that is never requested and never stored.
     */
    private val hotEvents = LinkedHashMap<String, NostrEvent>()

    private val metadataRequestedThisSession = mutableSetOf<String>()

    /**
     * Splits [ids] into the events already held and the ids still worth requesting.
     *
     * Safe to trust completely: an event id is a hash of its content, so a stored event with the
     * same id is the same event.
     *
     * The known events are returned rather than merely counted, and callers must fold them back
     * into their result. A thread builds its conversation cross-refs from the events in the
     * response, so dropping the cached ancestors instead of returning them would leave the
     * thread rendering with holes — faster and wrong.
     */
    suspend fun partitionKnownEventIds(ids: List<String>): CachedIds {
        val distinct = ids.distinct()
        val hot = mutex.withLock { distinct.mapNotNull { touch(it) } }
        val hotIds = hot.map { it.id }.toSet()
        val cold = distinct.filterNot { it in hotIds }
        if (cold.isEmpty()) return CachedIds(known = hot, missing = emptyList())

        val stored = database.posts().findPosts(postIds = cold)
        val decoded = stored.mapNotNull { it.raw.decodeFromJsonStringOrNull<NostrEvent>() }
        mutex.withLock { remember(decoded) }
        val decodedIds = decoded.map { it.id }.toSet()
        return CachedIds(known = hot + decoded, missing = cold.filterNot { it in decodedIds })
    }

    /**
     * Returns the pubkeys whose metadata has not been requested yet in this session.
     *
     * Marks them as requested, so two fetchers running at once do not both ask for the same
     * authors. Callers that genuinely need fresh metadata — the profile screen, say — should not
     * go through here. A caller that comes back empty-handed owes a
     * [releaseMetadataPubkeys].
     */
    suspend fun claimMetadataPubkeys(pubkeys: List<String>): List<String> {
        if (pubkeys.isEmpty()) return emptyList()
        return mutex.withLock {
            pubkeys.distinct().filter { metadataRequestedThisSession.add(it) }
        }
    }

    /**
     * Gives a claim back, so the pubkey can be asked for again.
     *
     * A claim taken and never returned is how a profile ends up rendered as a raw npub for the
     * rest of the session: one relay request that timed out or came back empty was enough to mark
     * the author as done, and nothing would ever ask a second time.
     */
    suspend fun releaseMetadataPubkeys(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        mutex.withLock { metadataRequestedThisSession.removeAll(pubkeys.toSet()) }
    }

    /**
     * Drops everything held for the current session.
     *
     * Called when a user's data is cleared: the next account should resolve its own profiles
     * rather than inherit a set of authors somebody else had already asked about.
     */
    suspend fun clearSession() =
        mutex.withLock {
            metadataRequestedThisSession.clear()
            hotEvents.clear()
        }

    /** Moves an event to the most-recently-used end. Call under [mutex]. */
    private fun touch(id: String): NostrEvent? {
        val event = hotEvents.remove(id) ?: return null
        hotEvents[id] = event
        return event
    }

    /** Adds events at the most-recently-used end, evicting the oldest. Call under [mutex]. */
    private fun remember(events: List<NostrEvent>) {
        events.forEach {
            hotEvents.remove(it.id)
            hotEvents[it.id] = it
        }
        while (hotEvents.size > maxHotEvents) {
            val oldest = hotEvents.keys.firstOrNull() ?: break
            hotEvents.remove(oldest)
        }
    }

    data class CachedIds(
        /** Events already held, decoded. Fold these back into the result. */
        val known: List<NostrEvent>,
        val missing: List<String>,
    )

    companion object {
        /**
         * Bounded because a long session reads a lot of notes and none of this is worth an
         * out-of-memory kill. Roughly the last few feed pages plus the threads opened from them.
         */
        const val MAX_HOT_EVENTS = 1_024
    }
}
