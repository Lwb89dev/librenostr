package net.primal.core.networking.tor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Test

class RouteDnsTest {

    private val looked = mutableListOf<String>()
    private val sentinel = Dns { hostname ->
        looked += hostname
        listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
    }

    private fun dns(mode: NetworkMode) =
        RouteDns(
            config = { RouteConfig(mode = mode, engine = TorEngineType.BUILT_IN, orbotPort = 9050) },
            delegate = sentinel,
        )

    @Test
    fun `direct mode resolves ordinary names through the system resolver`() {
        dns(NetworkMode.DIRECT).lookup("relay.example").size shouldBe 1

        looked shouldBe listOf("relay.example")
    }

    @Test
    fun `an onion name is never given to the resolver, in any mode`() {
        for (mode in NetworkMode.entries) {
            shouldThrow<UnknownHostException> { dns(mode).lookup("abcdefghijklmnop.onion") }
            shouldThrow<UnknownHostException> { dns(mode).lookup("ABCDEFGHIJKLMNOP.ONION.") }
        }

        looked shouldBe emptyList()
    }

    @Test
    fun `Tor mode resolves nothing locally`() {
        shouldThrow<UnknownHostException> { dns(NetworkMode.TOR).lookup("relay.example") }

        looked shouldBe emptyList()
    }

    @Test
    fun `onion-only mode resolves the rest of the internet as usual`() {
        dns(NetworkMode.ONION_ONLY).lookup("relay.example").size shouldBe 1

        looked shouldBe listOf("relay.example")
    }
}
