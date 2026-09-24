package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test

/**
 * Long-lived connections need their own tests: a relay socket is not a request that finishes, it stays
 * up for as long as the relay allows, so a mode switch has to reach it deliberately.
 */
class NetworkRouteWebSocketTest {

    private val web = FakeHttpServer()
    private val controller = RouteController(config(NetworkMode.DIRECT))

    @After
    fun tearDown() {
        web.close()
    }

    private fun config(mode: NetworkMode) = RouteConfig(mode = mode, engine = TorEngineType.ORBOT, orbotPort = 9050)

    /** Reports when a WebSocket is open and when it ends, however it ends. */
    private class WebSocketProbe : WebSocketListener() {
        val opened = CountDownLatch(1)
        val ended = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended.countDown()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended.countDown()
    }

    private fun openDirectWebSocket(): WebSocketProbe {
        val client = OkHttpClient.Builder()
            .applyNetworkRoute(RouteRuntime(controller, builtInPort = { null }, awaitBuiltInPort = { null }))
            .build()
        val probe = WebSocketProbe()
        client.newWebSocket(Request.Builder().url("ws://localhost:${web.port}/").build(), probe)
        probe.opened.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        return probe
    }

    @Test
    fun `a WebSocket opened directly is closed when Tor is chosen`() {
        val probe = openDirectWebSocket()

        controller.update(config(NetworkMode.TOR))

        // A relay connection can stay up for days; one opened directly must not outlive the switch.
        probe.ended.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a WebSocket is left alone when the mode did not really change`() {
        val probe = openDirectWebSocket()

        controller.update(config(NetworkMode.DIRECT))

        probe.ended.await(SETTLE_MILLIS, TimeUnit.MILLISECONDS) shouldBe false
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val SETTLE_MILLIS = 500L
    }
}
