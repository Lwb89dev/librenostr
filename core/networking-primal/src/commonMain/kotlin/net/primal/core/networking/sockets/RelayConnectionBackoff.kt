package net.primal.core.networking.sockets

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import net.primal.domain.common.exception.NetworkException

/**
 * Remembers which relays recently refused a connection, so the app stops dialling them on every
 * single request and fails their share of the work instantly instead.
 *
 * Why this exists: sockets here connect on demand — the first REQ to a relay opens its connection —
 * and a single feed page fans out into dozens of queries, each of which used to try to (re)connect
 * every relay that was not connected. For a relay that is down, that meant dozens of TLS handshakes
 * per page, each one serialized behind the client's connection lock, each one able to take up to the
 * OkHttp connect timeout (10 s direct, 30 s over Tor) when the host silently drops packets. Queries
 * were waiting on that lock while holding one of the relay pool's few query slots, so one dead relay
 * could starve the slots and with them every feed, thread, notification and DM load in the app.
 *
 * The schedule follows Amethyst's `BasicRelayClient` (exponential, capped at five minutes, an
 * explicit HTTP refusal of the WebSocket upgrade escalating faster), adapted to on-demand connecting:
 * - every failed attempt pushes the next allowed attempt out by the current delay, which then
 *   doubles, starting at [INITIAL_DELAY] and capped at [MAX_DELAY];
 * - a server that answered the upgrade with an HTTP status (503, 403, …) is alive but refusing, so
 *   its delay jumps straight to at least [REFUSED_DELAY] instead of climbing there one second at a
 *   time;
 * - a DNS failure is deliberately *not* escalated like that (Amethyst does): on a phone it is far
 *   more often the device being offline than the relay's domain being gone, and escalating every
 *   relay at once on a dead network would keep the app dark for minutes after it comes back;
 * - the delay only resets once a connection has stayed up for [STABLE_CONNECTION], so a relay that
 *   accepts the handshake and drops the socket right after does not get hammered in a tight loop;
 * - [resetAll] forgives everything when the device's network changes, because a failure on the old
 *   network says nothing about the new one, and [reset] does the same for one relay when the user
 *   explicitly asks to reconnect it.
 *
 * Keyed by the cleaned socket URL and shared process-wide by default ([Shared]), so a relay that is
 * dead for the account pool is also known dead for the public fallback pool, the outbox enrichment
 * pool and the throwaway pools built per DM operation, instead of each of them rediscovering it.
 */
@OptIn(ExperimentalAtomicApi::class)
class RelayConnectionBackoff(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {

    private data class Entry(
        /** No new connection attempt before this mark. */
        val retryAt: ComparableTimeMark,
        /** The delay the *next* failure will add, already doubled. */
        val nextDelay: Duration,
        /** When the last successful connection opened, null if none since the last failure. */
        val connectedAt: ComparableTimeMark? = null,
    )

    // Copy-on-write map behind an atomic reference: updates are rare (a connect attempt finishing),
    // reads happen on every request, and this keeps both lock-free and safe across threads in
    // common code, where `synchronized` is not available.
    private val entries = AtomicReference<Map<String, Entry>>(emptyMap())

    /**
     * How much longer [url] must wait before the next connection attempt, or null when an attempt
     * may go out right now.
     */
    fun remainingDelay(url: String): Duration? {
        val entry = entries.load()[url] ?: return null
        val remaining = -entry.retryAt.elapsedNow()
        return remaining.takeIf { it.isPositive() }
    }

    /**
     * Records a failed connection attempt and schedules the next allowed one.
     *
     * @param refusedByServer the relay answered the upgrade request with an HTTP status instead of
     *   switching protocols, i.e. it is reachable but explicitly not accepting us right now.
     */
    fun recordFailure(url: String, refusedByServer: Boolean) {
        entries.update { current ->
            val previous = current[url]
            val wasStable = previous?.connectedAt?.let { it.elapsedNow() >= STABLE_CONNECTION } == true
            val delay = when {
                previous == null || wasStable -> INITIAL_DELAY
                else -> previous.nextDelay
            }.let { if (refusedByServer) maxOf(it, REFUSED_DELAY) else it }.coerceAtMost(MAX_DELAY)
            val entry = Entry(
                retryAt = timeSource.markNow() + delay,
                nextDelay = (delay * 2).coerceAtMost(MAX_DELAY),
            )
            current + (url to entry)
        }
    }

    /**
     * Records a connection that opened. The accumulated delay is kept on purpose (see the class
     * comment): it is only forgiven once this connection proves stable, which [recordFailure]
     * checks the next time something goes wrong.
     */
    fun recordSuccess(url: String) {
        entries.update { current ->
            val previous = current[url] ?: return@update current
            current + (url to previous.copy(retryAt = timeSource.markNow(), connectedAt = timeSource.markNow()))
        }
    }

    /** Forgets everything known about [url]; its next request connects immediately. */
    fun reset(url: String) {
        entries.update { it - url }
    }

    /** Forgets every relay's backoff, e.g. because the device moved to another network. */
    fun resetAll() {
        entries.store(emptyMap())
    }

    companion object {
        val INITIAL_DELAY = 2.seconds
        val REFUSED_DELAY = 1.minutes
        val MAX_DELAY = 5.minutes
        val STABLE_CONNECTION = 1.minutes

        /** The process-wide instance every production socket client shares. */
        val Shared = RelayConnectionBackoff()
    }
}

/**
 * Thrown instead of dialling a relay that is still inside its [RelayConnectionBackoff] window. It is
 * a [NetworkException] so every caller already handles it exactly like a failed connection — it just
 * costs no network round-trip at all.
 */
class RelayBackingOffException(url: String, remaining: Duration) :
    NetworkException("$url is backing off for another $remaining")
