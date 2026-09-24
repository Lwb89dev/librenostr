package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test

class RouteTimeoutInterceptorTest {

    private class FakeChain(
        private val request: Request,
        private val connectMs: Int,
        private val readMs: Int,
        private val writeMs: Int,
        private val proceeded: MutableList<FakeChain>,
    ) : Interceptor.Chain {
        val timeouts get() = Triple(connectMs, readMs, writeMs)

        override fun request() = request

        override fun proceed(request: Request): Response {
            proceeded += this
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException()

        override fun connectTimeoutMillis() = connectMs

        override fun readTimeoutMillis() = readMs

        override fun writeTimeoutMillis() = writeMs

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) =
            FakeChain(request, unit.toMillis(timeout.toLong()).toInt(), readMs, writeMs, proceeded)

        override fun withReadTimeout(timeout: Int, unit: TimeUnit) =
            FakeChain(request, connectMs, unit.toMillis(timeout.toLong()).toInt(), writeMs, proceeded)

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) =
            FakeChain(request, connectMs, readMs, unit.toMillis(timeout.toLong()).toInt(), proceeded)
    }

    private fun run(mode: NetworkMode, url: String, timeouts: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val proceeded = mutableListOf<FakeChain>()
        val chain = FakeChain(
            request = Request.Builder().url(url).build(),
            connectMs = timeouts.first,
            readMs = timeouts.second,
            writeMs = timeouts.third,
            proceeded = proceeded,
        )
        val config = RouteConfig(mode = mode, engine = TorEngineType.BUILT_IN, orbotPort = 9050)

        RouteTimeoutInterceptor { config }.intercept(chain)

        return proceeded.single().timeouts
    }

    @Test
    fun `calls that go through Tor get the wider timeouts`() {
        run(NetworkMode.TOR, "https://relay.example/", Triple(10_000, 10_000, 10_000)) shouldBe
            Triple(TOR_TIMEOUT_MS, TOR_TIMEOUT_MS, TOR_TIMEOUT_MS)
    }

    @Test
    fun `direct calls keep exactly the timeouts they had`() {
        run(NetworkMode.DIRECT, "https://relay.example/", Triple(10_000, 10_000, 10_000)) shouldBe
            Triple(10_000, 10_000, 10_000)
    }

    @Test
    fun `in onion-only mode only the onion call is widened`() {
        val original = Triple(10_000, 10_000, 10_000)

        run(NetworkMode.ONION_ONLY, "http://abcdefghijklmnop.onion/", original) shouldBe
            Triple(TOR_TIMEOUT_MS, TOR_TIMEOUT_MS, TOR_TIMEOUT_MS)
        run(NetworkMode.ONION_ONLY, "https://relay.example/", original) shouldBe original
    }

    @Test
    fun `a caller that asked for longer keeps it`() {
        run(NetworkMode.TOR, "https://relay.example/", Triple(60_000, 5_000, 90_000)) shouldBe
            Triple(60_000, TOR_TIMEOUT_MS, 90_000)
    }
}
