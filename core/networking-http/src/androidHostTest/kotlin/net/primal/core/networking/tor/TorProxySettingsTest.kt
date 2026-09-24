package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
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
}
