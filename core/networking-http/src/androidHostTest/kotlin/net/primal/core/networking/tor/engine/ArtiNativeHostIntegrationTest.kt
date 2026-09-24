package net.primal.core.networking.tor.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole chain on the host: Kotlin engine, JNI, the Rust library and the real Tor network.
 *
 * What it proves that nothing else does: that the `external` declarations in [ArtiNative] match the
 * symbols the library actually exports (a renamed method or package fails only at runtime, as an
 * `UnsatisfiedLinkError`), and that [ArtiTorEngine]'s use of the bridge produces a working proxy.
 *
 * Skipped unless `-Pnostr.arti.hostLib=<dir>` is given (see the module's build file), because it
 * needs the library built for the host and outbound access to the Tor network.
 */
class ArtiNativeHostIntegrationTest {

    private val hostLib: String? = System.getProperty("nostr.arti.hostLib")

    private fun engine(dataDir: File): Pair<ArtiTorEngine, () -> Unit> {
        val executor = Executors.newCachedThreadPool()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val engine = ArtiTorEngine(
            bridge = JniArtiBridge(),
            dataDir = dataDir,
            scope = scope,
            dispatcher = executor.asCoroutineDispatcher(),
        )
        return engine to { executor.shutdownNow() }
    }

    private suspend fun ArtiTorEngine.awaitReady(): Int {
        val ready = withTimeout(READY_TIMEOUT) { state.first { it.isReady || it is TorEngineState.Failed } }
        ready.shouldBe(TorEngineState.Ready(requireNotNull(ready.socksPort)))
        return requireNotNull(ready.socksPort)
    }

    private fun isTorViaProxy(port: Int): String {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(java.time.Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
            .build()
        client.newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use {
            return it.body?.string().orEmpty()
        }
    }

    @Test
    fun `the Kotlin engine drives the real library and a request exits through Tor`() {
        assumeTrue("set -Pnostr.arti.hostLib to run", hostLib != null)
        val dataDir = createTempDirectory("arti-host-it").toFile()
        val (engine, shutdown) = engine(dataDir)
        try {
            runBlocking {
                engine.start()
                // The proxy is routable as soon as start() returns, before the directory has landed.
                (engine.state.value.socksPort != null) shouldBe true
                val port = engine.awaitReady()

                isTorViaProxy(port) shouldContain "\"IsTor\":true"
                (engine.health().connectOk >= 1) shouldBe true

                // A gentle restart must come back on a (possibly different) port and still work: that
                // is what the supervisor does to a wedged client, and the state-file lock has to be
                // released for it to succeed.
                engine.restart(wipeState = false)
                val portAfterRestart = engine.awaitReady()
                isTorViaProxy(portAfterRestart) shouldContain "\"IsTor\":true"

                engine.stop()
                engine.state.value shouldBe TorEngineState.Off
            }
        } finally {
            shutdown()
            dataDir.deleteRecursively()
        }
    }

    private companion object {
        val READY_TIMEOUT = 240.seconds
        const val CALL_TIMEOUT_SECONDS = 90L
    }
}
