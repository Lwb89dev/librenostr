package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Test

class TorPortProxySelectorTest {

    private val uri = URI("https://relay.example/")

    private fun Proxy.port() = (address() as InetSocketAddress).port

    @Test
    fun `a known port is used at once without waiting`() {
        var waited = false
        val selector = TorPortProxySelector(currentPort = { 41_000 }, awaitPort = { waited = true; null })

        val proxy = selector.select(uri).single()

        proxy.type() shouldBe Proxy.Type.SOCKS
        proxy.port() shouldBe 41_000
        waited shouldBe false
    }

    @Test
    fun `an engine that is still starting is waited for`() {
        var requestedWaitMs = -1L
        val selector = TorPortProxySelector(
            currentPort = { null },
            awaitPort = { timeout -> requestedWaitMs = timeout; 42_000 },
            waitForPortMs = 1_234L,
        )

        selector.select(uri).single().port() shouldBe 42_000
        requestedWaitMs shouldBe 1_234L
    }

    @Test
    fun `the port is asked again on every connection so an engine restart is followed`() {
        var port: Int? = 40_001
        val selector = TorPortProxySelector(currentPort = { port }, awaitPort = { null })

        selector.select(uri).single().port() shouldBe 40_001
        port = 40_002
        selector.select(uri).single().port() shouldBe 40_002
    }

    @Test
    fun `with no port at all it fails closed instead of going direct`() {
        val selector = TorPortProxySelector(currentPort = { null }, awaitPort = { null })

        val proxies = selector.select(uri)

        // NO_PROXY here would send the request straight out, the one thing Tor mode must never do.
        proxies.none { it == Proxy.NO_PROXY } shouldBe true
        proxies.single().type() shouldBe Proxy.Type.SOCKS
        proxies.single().port() shouldBe 1
    }
}
