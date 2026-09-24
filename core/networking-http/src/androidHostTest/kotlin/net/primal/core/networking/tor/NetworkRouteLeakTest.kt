package net.primal.core.networking.tor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.ServerSocket
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The tests that matter most for Tor mode: real OkHttp clients built through [applyNetworkRoute],
 * talking to a real (fake) SOCKS5 proxy and a real web server, checking what actually left the
 * process rather than what a setting says.
 *
 * Every "went through Tor" assertion is made from the proxy's side (it saw the name, unresolved) and
 * every "did not leak" assertion from the direct server's side (it saw no request).
 */
class NetworkRouteLeakTest {

    private val socks = FakeSocksServer()
    private val web = FakeHttpServer()
    private lateinit var controller: RouteController

    @Before
    fun setUp() {
        controller = RouteController(config(NetworkMode.DIRECT))
    }

    @After
    fun tearDown() {
        socks.close()
        web.close()
    }

    private fun config(mode: NetworkMode, orbotPort: Int = socks.port) =
        RouteConfig(mode = mode, engine = TorEngineType.ORBOT, orbotPort = orbotPort)

    private fun client(): OkHttpClient =
        OkHttpClient.Builder()
            .applyNetworkRoute(RouteRuntime(controller, builtInPort = { null }, awaitBuiltInPort = { null }))
            .build()

    private fun OkHttpClient.fetch(url: String): String =
        newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    private fun webUrl() = web.url()

    @Test
    fun `in Tor mode a name reaches the proxy unresolved and is never looked up locally`() {
        controller.update(config(NetworkMode.TOR))

        client().fetch("http://abcdefghijklmnop.onion/") shouldBe "ok"

        // A domain-name request (type 3), not an address: the client resolved nothing itself. If it had
        // tried, RouteDns would have refused in Tor mode and the call above would have failed.
        socks.requests.single().let {
            it.isDomainName shouldBe true
            it.host shouldBe "abcdefghijklmnop.onion"
            it.port shouldBe 80
        }
    }

    @Test
    fun `in Tor mode a clearnet name is handed to the proxy too, not resolved here`() {
        controller.update(config(NetworkMode.TOR))

        client().fetch("http://relay.example.com/") shouldBe "ok"

        socks.requests.single().let {
            it.isDomainName shouldBe true
            it.host shouldBe "relay.example.com"
        }
    }

    @Test
    fun `in Tor mode a dead proxy makes the call fail instead of going out directly`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        controller.update(config(NetworkMode.TOR, orbotPort = closedPort))

        // The web server is reachable directly, which is exactly what must not happen.
        shouldThrow<IOException> { client().fetch(webUrl()) }

        web.requestCount shouldBe 0
    }

    @Test
    fun `in onion-only mode onion goes through the proxy and everything else goes direct`() {
        controller.update(config(NetworkMode.ONION_ONLY))
        val client = client()

        client.fetch("http://abcdefghijklmnop.onion/") shouldBe "ok"
        client.fetch(webUrl()) shouldBe "direct"

        socks.requests.map { it.host } shouldBe listOf("abcdefghijklmnop.onion")
        web.requestCount shouldBe 1
    }

    @Test
    fun `in direct mode an onion name is refused and is neither resolved nor proxied`() {
        // Direct mode is also what a user is in when they open an onion link without Tor: it must not
        // become a DNS query for the onion name.
        shouldThrow<UnknownHostException> { client().fetch("http://abcdefghijklmnop.onion/") }

        socks.requests shouldBe emptyList()
    }

    @Test
    fun `one client follows a mode switch with no restart`() {
        val client = client()

        client.fetch(webUrl()) shouldBe "direct"
        web.requestCount shouldBe 1

        controller.update(config(NetworkMode.TOR))
        client.fetch(webUrl()) shouldBe "ok"

        // The second request went to the proxy, and the web server saw nothing more.
        web.requestCount shouldBe 1
        socks.requests.single().port shouldBe web.port
    }

    @Test
    fun `switching to Tor closes the idle direct connections`() {
        val client = client()
        client.fetch(webUrl())
        client.connectionPool.connectionCount() shouldBe 1

        controller.update(config(NetworkMode.TOR))

        client.connectionPool.connectionCount() shouldBe 0
    }

    /**
     * A client with the guard but without the socket tracking that closes direct sockets on a switch,
     * so that a pooled direct connection genuinely survives the switch and the guard is what stops it.
     */
    private fun clientWithGuardOnly(): OkHttpClient {
        val config = { controller.config }
        return OkHttpClient.Builder()
            .proxySelector(RouteProxySelector(config, builtInPort = { null }, awaitBuiltInPort = { null }))
            .dns(RouteDns(config))
            .addNetworkInterceptor(RouteGuardInterceptor(config))
            .build()
    }

    @Test
    fun `a pooled direct connection that survived the switch is refused for Tor traffic`() {
        web.enqueue("first")
        val client = clientWithGuardOnly()
        val inFlight = client.newCall(Request.Builder().url(webUrl()).build()).execute()

        // The connection is in use, so it stays open across the switch and goes back to the pool afterwards.
        controller.update(config(NetworkMode.TOR))
        inFlight.body.string() shouldBe "first"

        // Without the guard, OkHttp would happily reuse that pooled direct connection here.
        shouldThrow<IOException> { client.fetch(webUrl()) }
        web.requestCount shouldBe 1
        socks.requests shouldBe emptyList()

        // The offending connection was dropped, so the next attempt opens a proper Tor one.
        client.fetch(webUrl()) shouldBe "ok"
        web.requestCount shouldBe 1
    }
}
