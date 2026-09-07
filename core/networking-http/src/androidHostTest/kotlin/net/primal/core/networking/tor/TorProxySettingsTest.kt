package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import java.net.Proxy
import okhttp3.OkHttpClient
import org.junit.Test

class TorProxySettingsTest {

    @Test
    fun `default settings are disabled with Orbot's default port`() {
        val settings = TorProxySettings()
        settings.enabled shouldBe false
        settings.socksPort shouldBe DEFAULT_ORBOT_SOCKS_PORT
    }

    @Test
    fun `isValidSocksPort accepts the full valid port range`() {
        MIN_SOCKS_PORT.isValidSocksPort() shouldBe true
        MAX_SOCKS_PORT.isValidSocksPort() shouldBe true
        DEFAULT_ORBOT_SOCKS_PORT.isValidSocksPort() shouldBe true
    }

    @Test
    fun `isValidSocksPort rejects out-of-range values`() {
        0.isValidSocksPort() shouldBe false
        (-1).isValidSocksPort() shouldBe false
        (MAX_SOCKS_PORT + 1).isValidSocksPort() shouldBe false
    }

    @Test
    fun `applyTorProxyIfEnabled is a no-op when Tor is disabled`() {
        val client = OkHttpClient.Builder()
            .applyTorProxyIfEnabled(TorProxySettings(enabled = false))
            .build()

        client.proxy shouldBe null
    }

    @Test
    fun `applyTorProxyIfEnabled sets a SOCKS proxy at 127-0-0-1 when enabled`() {
        val port = 9150
        val client = OkHttpClient.Builder()
            .applyTorProxyIfEnabled(TorProxySettings(enabled = true, socksPort = port))
            .build()

        val proxy = client.proxy
        checkNotNull(proxy)
        proxy.type() shouldBe Proxy.Type.SOCKS
        proxy.address().toString() shouldBe "/127.0.0.1:$port"
    }
}
