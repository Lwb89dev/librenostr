package net.primal.data.repository.fetch

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The gate's whole job is bounding how many wrapped queries run at once, so every test here is
 * about the peak concurrency a batch of calls actually reaches.
 */
class SessionSyncRelayGateTest {

    @Test
    fun `wrap bounds concurrency to the configured maximum`() = runTest {
        val querier = ConcurrencyTrackingQuerier()
        val wrapped = SessionSyncRelayGate(maxConcurrent = 2).wrap(querier)

        (1..6).map { async { wrapped.query(RelayFilter()) } }.awaitAll()

        assertEquals(2, querier.peakConcurrency, "no more than the configured permits should run at once")
        assertEquals(6, querier.queryCount)
    }

    @Test
    fun `an unwrapped querier is not throttled by the gate`() = runTest {
        val querier = ConcurrencyTrackingQuerier()

        (1..6).map { async { querier.query(RelayFilter()) } }.awaitAll()

        assertTrue(querier.peakConcurrency > 2, "without the gate, all six calls should overlap")
    }

    private class ConcurrencyTrackingQuerier : RelayEventQuerier {
        private val current = AtomicInteger(0)
        private val peak = AtomicInteger(0)
        private val calls = AtomicInteger(0)

        val peakConcurrency: Int get() = peak.get()
        val queryCount: Int get() = calls.get()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            calls.incrementAndGet()
            val inFlight = current.incrementAndGet()
            peak.updateAndGet { maxOf(it, inFlight) }
            delay(10)
            current.decrementAndGet()
            return emptyList()
        }
    }
}
