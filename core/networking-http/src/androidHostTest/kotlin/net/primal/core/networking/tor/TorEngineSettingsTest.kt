package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.Proxy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Test

class TorEngineSettingsTest {

    private suspend fun decode(json: String): TorProxySettings =
        TorProxySettingsSerialization.readFrom(Buffer().writeUtf8(json))

    @Test
    fun `settings saved before the engine option existed keep meaning Orbot`() =
        runTest {
            // The exact shape every existing install has on disk.
            val settings = decode("""{"enabled":true,"socksPort":9051}""")

            settings shouldBe TorProxySettings(enabled = true, socksPort = 9051, engine = TorEngineType.ORBOT)
        }

    @Test
    fun `the built-in engine round-trips`() =
        runTest {
            val settings = decode("""{"enabled":true,"socksPort":9050,"engine":"BUILT_IN"}""")

            settings.engine shouldBe TorEngineType.BUILT_IN
        }

    @Test
    fun `an engine name from a future version falls back to Orbot instead of failing to load`() =
        runTest {
            // coerceInputValues: a DataStore that cannot decode its file would reset the user's settings.
            val settings = decode("""{"enabled":true,"engine":"SOMETHING_NEW"}""")

            settings.engine shouldBe TorEngineType.ORBOT
            settings.enabled shouldBe true
        }

    @Test
    fun `the default engine is Orbot`() {
        TorProxySettings().engine shouldBe TorEngineType.ORBOT
    }

    @Test
    fun `Orbot still gets a fixed SOCKS proxy at the configured port`() {
        val client = OkHttpClient.Builder()
            .applyTorProxyIfEnabled(TorProxySettings(enabled = true, socksPort = 9123, engine = TorEngineType.ORBOT))
            .build()

        client.proxy?.type() shouldBe Proxy.Type.SOCKS
    }

    @Test
    fun `the built-in engine resolves its port per connection instead of baking one in`() {
        val client = OkHttpClient.Builder()
            .applyTorProxyIfEnabled(TorProxySettings(enabled = true, engine = TorEngineType.BUILT_IN))
            .build()

        // No fixed proxy: the engine's port changes every time it restarts.
        client.proxy shouldBe null
        client.proxySelector.shouldBeInstanceOf<TorPortProxySelector>()
    }

    @Test
    fun `nothing is applied when Tor is off, whatever the engine`() {
        val client = OkHttpClient.Builder()
            .applyTorProxyIfEnabled(TorProxySettings(enabled = false, engine = TorEngineType.BUILT_IN))
            .build()

        client.proxy shouldBe null
        client.proxySelector.shouldBeInstanceOf<java.net.ProxySelector>()
        (client.proxySelector is TorPortProxySelector) shouldBe false
    }
}
