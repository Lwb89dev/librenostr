package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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
    fun `settings saved before network modes existed keep their meaning`() =
        runTest {
            // "enabled" used to mean everything through Tor with no fallback, which is exactly TOR.
            decode("""{"enabled":true,"socksPort":9050}""").effectiveMode shouldBe NetworkMode.TOR
            decode("""{"enabled":false,"socksPort":9050}""").effectiveMode shouldBe NetworkMode.DIRECT
            decode("""{}""").effectiveMode shouldBe NetworkMode.DIRECT
        }

    @Test
    fun `an explicit mode wins over the legacy flag`() =
        runTest {
            val settings = decode("""{"enabled":false,"mode":"ONION_ONLY"}""")

            settings.effectiveMode shouldBe NetworkMode.ONION_ONLY
        }

    @Test
    fun `a mode name from a future version falls back to what the legacy flag says`() =
        runTest {
            val settings = decode("""{"enabled":true,"mode":"SOMETHING_NEW"}""")

            settings.effectiveMode shouldBe NetworkMode.TOR
        }

    @Test
    fun `withMode keeps the legacy flag consistent for anything that still reads it`() {
        val base = TorProxySettings()

        base.withMode(NetworkMode.TOR).enabled shouldBe true
        base.withMode(NetworkMode.ONION_ONLY).enabled shouldBe true
        base.withMode(NetworkMode.TOR).withMode(NetworkMode.DIRECT).enabled shouldBe false
        base.withMode(NetworkMode.ONION_ONLY).effectiveMode shouldBe NetworkMode.ONION_ONLY
    }

    @Test
    fun `the built-in engine runs only when Tor carries traffic and it is the chosen engine`() {
        val builtIn = TorProxySettings(engine = TorEngineType.BUILT_IN)
        val orbot = TorProxySettings(engine = TorEngineType.ORBOT)

        builtIn.withMode(NetworkMode.DIRECT).wantsBuiltInEngine shouldBe false
        builtIn.withMode(NetworkMode.TOR).wantsBuiltInEngine shouldBe true
        builtIn.withMode(NetworkMode.ONION_ONLY).wantsBuiltInEngine shouldBe true
        orbot.withMode(NetworkMode.TOR).wantsBuiltInEngine shouldBe false
    }
}
