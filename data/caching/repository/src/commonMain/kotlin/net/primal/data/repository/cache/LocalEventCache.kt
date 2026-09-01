package net.primal.data.repository.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.nostr.NostrEvent

/**
 * Keeps the relay fetchers from asking for things they already hold.
 *
 * Two kinds of waste were being paid on every page and every thread:
 *
 *  - **events requested by id.** A thread's ancestors, and the notes a notification refers to,
 *    are usually already in the database because the feed put them there. Nostr events are
 *    immutable and content-addressed, so a locally held id can be dropped from the filter with
 *    no staleness risk at all.
 *  - **profile metadata.** Every page re-requested kind 0 for the same authors. Profiles do
 *    change, so this is deduplicated per session rather than permanently: a pubkey is asked for
 *    once while the app is running, which removes the repetition without freezing a display name
 *    until the cache is cleared.
 *
 * Deliberately not cached: anything whose value is the freshness itself — interaction counters,
 * zap receipts, notification pages.
 */
internal class LocalEventCache(
    private val database: CachingDatabase,
) {

    private val mutex = Mutex()
    private val metadataRequestedThisSession = mutableSetOf<String>()

    /**
     * Splits [ids] into the events already stored and the ids still worth requesting.
     *
     * Safe to trust completely: an event id is a hash of its content, so a stored event with the
     * same id is the same event.
     *
     * The stored events are returned rather than merely counted, and callers must fold them back
     * into their result. A thread builds its conversation cross-refs from the events in the
     * response, so dropping the cached ancestors instead of returning them would leave the
     * thread rendering with holes — faster and wrong.
     */
    suspend fun partitionKnownEventIds(ids: List<String>): CachedIds {
        if (ids.isEmpty()) return CachedIds(known = emptyList(), missing = emptyList())
        val distinct = ids.distinct()
        val stored = database.posts().findPosts(postIds = distinct)
        val known = stored.mapNotNull { it.raw.decodeFromJsonStringOrNull<NostrEvent>() }
        val knownIds = known.map { it.id }.toSet()
        return CachedIds(known = known, missing = distinct.filterNot { it in knownIds })
    }

    /**
     * Returns the pubkeys whose metadata has not been requested yet in this session.
     *
     * Marks them as requested, so two fetchers running at once do not both ask for the same
     * authors. Callers that genuinely need fresh metadata — the profile screen, say — should not
     * go through here.
     */
    suspend fun claimMetadataPubkeys(pubkeys: List<String>): List<String> {
        if (pubkeys.isEmpty()) return emptyList()
        return mutex.withLock {
            pubkeys.distinct().filter { metadataRequestedThisSession.add(it) }
        }
    }

    /** Drops the session dedupe, so the next request refetches. Used when the account changes. */
    suspend fun clearSession() = mutex.withLock { metadataRequestedThisSession.clear() }

    data class CachedIds(
        /** Events already stored, decoded from their raw JSON. Fold these back into the result. */
        val known: List<NostrEvent>,
        val missing: List<String>,
    )
}
