package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Test

class RouteProxySelectorTest {

    private val clearnet = URI("https://relay.example/")
    private val onion = URI("http://abcdefghijklmnop.onion/")

    private var config = RouteConfig(mode = NetworkMode.TOR, engine = TorEngineType.BUILT_IN, orbotPort = 9123)
    private var builtInPort: Int? = 41_000
    private var waitedForMs: Long? = null
    private var portAfterWaiting: Int? = null

    private fun selector(waitForPortMs: Long = 5_000L) =
        RouteProxySelector(
            config = { config },
            builtInPort = { builtInPort },
            awaitBuiltInPort = { timeout ->
                waitedForMs = timeout
                portAfterWaiting
            },
            waitForPortMs = waitForPortMs,
        )

    private fun Proxy.port() = (address() as InetSocketAddress).port

    private fun mode(mode: NetworkMode, engine: TorEngineType = TorEngineType.BUILT_IN) {
        config = config.copy(mode = mode, engine = engine)
    }

    @Test
    fun `direct mode never uses a proxy`() {
        mode(NetworkMode.DIRECT)

        selector().select(clearnet) shouldBe listOf(Proxy.NO_PROXY)
        selector().select(onion) shouldBe listOf(Proxy.NO_PROXY)
    }

    @Test
    fun `Tor mode sends everything to the built-in engine's port without waiting when it is known`() {
        mode(NetworkMode.TOR)

        val proxy = selector().select(clearnet).single()

        proxy.type() shouldBe Proxy.Type.SOCKS
        proxy.port() shouldBe 41_000
        waitedForMs shouldBe null
    }

    @Test
    fun `Tor mode with Orbot uses the configured port and never asks the built-in engine`() {
        mode(NetworkMode.TOR, engine = TorEngineType.ORBOT)
        builtInPort = null

        val proxy = selector().select(clearnet).single()

        proxy.port() shouldBe 9123
        waitedForMs shouldBe null
    }

    @Test
    fun `an engine that is still starting is waited for`() {
        mode(NetworkMode.TOR)
        builtInPort = null
        portAfterWaiting = 42_000

        selector(waitForPortMs = 1_234L).select(clearnet).single().port() shouldBe 42_000
        waitedForMs shouldBe 1_234L
    }

    @Test
    fun `the port is asked again on every connection so an engine restart is followed`() {
        mode(NetworkMode.TOR)
        val selector = selector()

        builtInPort = 40_001
        selector.select(clearnet).single().port() shouldBe 40_001
        builtInPort = 40_002
        selector.select(clearnet).single().port() shouldBe 40_002
    }

    @Test
    fun `Tor mode with no port at all fails closed instead of going direct`() {
        mode(NetworkMode.TOR)
        builtInPort = null
        portAfterWaiting = null

        val proxies = selector().select(clearnet)

        // NO_PROXY here would send the request straight out, the one thing Tor mode must never do.
        proxies.none { it == Proxy.NO_PROXY } shouldBe true
        proxies.single().type() shouldBe Proxy.Type.SOCKS
        proxies.single().port() shouldBe 1
    }

    @Test
    fun `onion-only mode proxies onion names and lets everything else out directly`() {
        mode(NetworkMode.ONION_ONLY)

        selector().select(onion).single().type() shouldBe Proxy.Type.SOCKS
        selector().select(clearnet) shouldBe listOf(Proxy.NO_PROXY)
    }

    @Test
    fun `onion-only mode also fails closed for an onion name when Tor has no port`() {
        mode(NetworkMode.ONION_ONLY)
        builtInPort = null
        portAfterWaiting = null

        val proxies = selector().select(onion)

        proxies.none { it == Proxy.NO_PROXY } shouldBe true
        proxies.single().port() shouldBe 1
    }

    @Test
    fun `the mode is read on every connection so a switch needs no new client`() {
        val selector = selector()

        mode(NetworkMode.DIRECT)
        selector.select(clearnet) shouldBe listOf(Proxy.NO_PROXY)
        mode(NetworkMode.TOR)
        selector.select(clearnet).single().type() shouldBe Proxy.Type.SOCKS
        mode(NetworkMode.DIRECT)
        selector.select(clearnet) shouldBe listOf(Proxy.NO_PROXY)
    }

    @Test
    fun `a request with no readable host is not sent directly in onion-only mode`() {
        mode(NetworkMode.ONION_ONLY)

        selector().select(null).single().type() shouldBe Proxy.Type.SOCKS
    }
}
