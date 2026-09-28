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
    fun `the relay covering the most authors is picked first`() {
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

        // "rare" is author1's relay too, but author1 is already reached through "popular" — adding
        // it would spend a slot on zero new coverage, so set cover stops after the one pick that
        // already reaches every author with any write relay at all.
        result shouldBe listOf(Relay(url = "wss://popular.example.com", read = true, write = false))
    }

    @Test
    fun `a relay unique to one otherwise-uncovered author is picked over a merely popular one`() {
        // Authors 1-8 all share both "popular" and "second" — either alone already reaches all
        // eight. Author 9 is reachable only through "niche". Picking by raw popularity ("popular"
        // and "second" are each used 8 times, "niche" once) fills a 2-relay budget with "popular"
        // and "second" and never reaches author9 at all.
        val sharedByEight = (1..8).map { i ->
            UserRelays(
                pubkey = "author$i",
                relays = listOf(
                    Relay(url = "wss://popular.example.com", read = true, write = true),
                    Relay(url = "wss://second.example.com", read = true, write = true),
                ),
            )
        }
        val niche = UserRelays(
            pubkey = "author9",
            relays = listOf(Relay(url = "wss://niche.example.com", read = true, write = true)),
        )

        val result = (sharedByEight + niche).topWriteRelays(maxRelays = 2)

        result shouldBe listOf(
            Relay(url = "wss://popular.example.com", read = true, write = false),
            Relay(url = "wss://niche.example.com", read = true, write = false),
        )
    }

    @Test
    fun `a tie in new coverage is broken by URL, so the result is deterministic`() {
        val userRelays = listOf(
            UserRelays(
                pubkey = "author1",
                relays = listOf(Relay(url = "wss://z.example.com", read = true, write = true)),
            ),
            UserRelays(
                pubkey = "author2",
                relays = listOf(Relay(url = "wss://a.example.com", read = true, write = true)),
            ),
        )

        userRelays.topWriteRelays(maxRelays = 1) shouldBe
            listOf(Relay(url = "wss://a.example.com", read = true, write = false))
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
