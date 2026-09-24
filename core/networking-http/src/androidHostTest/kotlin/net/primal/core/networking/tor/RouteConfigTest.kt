package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import org.junit.Test

class RouteConfigTest {

    private fun config(mode: NetworkMode) = RouteConfig(mode = mode, engine = TorEngineType.BUILT_IN, orbotPort = 9050)

    @Test
    fun `direct mode sends everything directly`() {
        config(NetworkMode.DIRECT).routeFor("relay.damus.io") shouldBe Route.DIRECT
        config(NetworkMode.DIRECT).routeFor(null) shouldBe Route.DIRECT
    }

    @Test
    fun `Tor mode sends everything through Tor, whatever the host`() {
        config(NetworkMode.TOR).routeFor("relay.damus.io") shouldBe Route.TOR
        config(NetworkMode.TOR).routeFor("abcdefghijklmnop.onion") shouldBe Route.TOR
        config(NetworkMode.TOR).routeFor("127.0.0.1") shouldBe Route.TOR
        config(NetworkMode.TOR).routeFor(null) shouldBe Route.TOR
    }

    @Test
    fun `onion-only mode sends onion names through Tor and the rest directly`() {
        val onlyOnion = config(NetworkMode.ONION_ONLY)

        onlyOnion.routeFor("abcdefghijklmnop.onion") shouldBe Route.TOR
        onlyOnion.routeFor("ABCDEFGHIJKLMNOP.ONION") shouldBe Route.TOR
        onlyOnion.routeFor("abcdefghijklmnop.onion.") shouldBe Route.TOR
        onlyOnion.routeFor("relay.damus.io") shouldBe Route.DIRECT
        onlyOnion.routeFor("abcdefghijklmnop.onion.example.com") shouldBe Route.DIRECT
    }

    @Test
    fun `onion-only mode does not gamble on a host it cannot read`() {
        // A guess of "direct" could send an onion name to a DNS resolver; "Tor" only costs latency.
        config(NetworkMode.ONION_ONLY).routeFor(null) shouldBe Route.TOR
    }
}
