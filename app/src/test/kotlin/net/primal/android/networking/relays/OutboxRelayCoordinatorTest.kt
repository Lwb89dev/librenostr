package net.primal.android.networking.relays

import io.kotest.matchers.shouldBe
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.UserRelays
import org.junit.Test

/**
 * Covers [topWriteRelays] only — the pure selection logic `OutboxRelayCoordinator` relies on.
 * Deliberately NOT exercising the coordinator's own `init` block/refresh loop here: it runs
 * `while (true) { refresh; delay(REFRESH_INTERVAL) }` on its own [kotlinx.coroutines.CoroutineScope]
 * by design, and driving that loop through `runTest`/virtual time was tried and repeatedly hung
 * the JVM at 100%+ CPU (`runTest`'s own end-of-test bookkeeping tries to drain every task still
 * pending on the shared `TestDispatcher`, and an endlessly-self-rescheduling `delay()` never
 * drains). The loop's own wiring mirrors `RelaysSocketManager.observeActiveUserId()`'s already
 * — and safely — tested `collectLatest` pattern; what's actually worth covering in isolation is
 * the selection math, which is why it was extracted into a plain top-level function in the first
 * place.
 */
class OutboxRelayCoordinatorTest {

    @Test
    fun `most common write relay across authors is returned first`() {
        val userRelays = listOf(
            UserRelays(
                pubkey = "author1",
                relays = listOf(
                    Relay(url = "wss://popular.example.com", read = true, write = true),
                    Relay(url = "wss://rare.example.com", read = true, write = true),
                ),
            ),
            UserRelays(
                pubkey = "author2",
                relays = listOf(
                    Relay(url = "wss://popular.example.com", read = true, write = true),
                ),
            ),
            UserRelays(
                pubkey = "author3",
                relays = listOf(
                    // Read-only for this author — must not count as a write relay.
                    Relay(url = "wss://popular.example.com", read = true, write = false),
                ),
            ),
        )

        val result = userRelays.topWriteRelays(maxRelays = 8)

        result shouldBe listOf(
            Relay(url = "wss://popular.example.com", read = true, write = false),
            Relay(url = "wss://rare.example.com", read = true, write = false),
        )
    }

    @Test
    fun `read-only relays never count towards the ranking`() {
        val userRelays = listOf(
            UserRelays(
                pubkey = "author1",
                relays = listOf(Relay(url = "wss://read-only.example.com", read = true, write = false)),
            ),
        )

        userRelays.topWriteRelays(maxRelays = 8) shouldBe emptyList()
    }

    @Test
    fun `result is capped at maxRelays even with many distinct write relays`() {
        val userRelays = (1..20).map { i ->
            UserRelays(
                pubkey = "author$i",
                relays = listOf(Relay(url = "wss://relay$i.example.com", read = true, write = true)),
            )
        }

        userRelays.topWriteRelays(maxRelays = 5).size shouldBe 5
    }

    @Test
    fun `every returned relay is read-only, regardless of what was passed in`() {
        val userRelays = listOf(
            UserRelays(
                pubkey = "author1",
                // Passed in as a write-and-read relay on purpose — the function must still
                // downgrade it, since this decides query coverage, never a publish target.
                relays = listOf(Relay(url = "wss://relay.example.com", read = true, write = true)),
            ),
        )

        val result = userRelays.topWriteRelays(maxRelays = 8)

        result.all { it.read && !it.write } shouldBe true
    }

    @Test
    fun `empty input returns an empty list`() {
        emptyList<UserRelays>().topWriteRelays(maxRelays = 8) shouldBe emptyList()
    }
}
