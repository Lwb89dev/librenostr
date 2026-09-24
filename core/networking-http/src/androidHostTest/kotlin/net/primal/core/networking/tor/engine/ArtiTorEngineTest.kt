package net.primal.core.networking.tor.engine

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class ArtiTorEngineTest {

    private lateinit var dataDir: File
    private val bridge = FakeArtiBridge()

    @Before
    fun setUp() {
        dataDir = createTempDirectory("arti-engine-test").toFile()
    }

    @After
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    private fun TestScope.engine() =
        ArtiTorEngine(
            bridge = bridge,
            dataDir = dataDir,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pollIntervalMs = 100L,
        )

    private fun writeGuards(vararg disabled: Boolean) {
        val guards = disabled.joinToString(prefix = """{"default":{"guards":[""", postfix = "]}}") {
            if (it) """{"disabled":{"reason":"x"},"unlisted_since":null,"confirmed_at":null}"""
            else """{"disabled":null,"unlisted_since":null,"confirmed_at":"2026-09-01T00:00:00Z"}"""
        }
        File(dataDir, ArtiTorEngine.GUARDS_FILE_PATH).apply { parentFile.mkdirs() }.writeText(guards)
    }

    @Test
    fun `start publishes the port as soon as the proxy is bound, then Ready once circuits can be built`() =
        runTest {
            val engine = engine()

            engine.start()

            // The proxy is routable while the directory is still downloading: dials queue behind it.
            engine.state.value shouldBe TorEngineState.Bootstrapping(port = 40_123, progressPermille = 0)
            engine.state.value.socksPort shouldBe 40_123
            engine.state.value.isReady shouldBe false

            bridge.bootstrapped = 1
            advanceTimeBy(200)

            engine.state.value shouldBe TorEngineState.Ready(port = 40_123)
        }

    @Test
    fun `the port is always requested as ephemeral`() =
        runTest {
            engine().start()

            bridge.calls shouldBe listOf("initialize", "startSocks(0)")
        }

    @Test
    fun `download progress is published while bootstrapping`() =
        runTest {
            val engine = engine()
            engine.start()

            bridge.progress = 420
            advanceTimeBy(150)

            engine.state.value shouldBe TorEngineState.Bootstrapping(port = 40_123, progressPermille = 420)
        }

    @Test
    fun `starting a running engine does nothing`() =
        runTest {
            val engine = engine()
            engine.start()

            engine.start()

            bridge.calls.count { it == "initialize" } shouldBe 1
        }

    @Test
    fun `a failed initialize leaves Failed and the next start retries`() =
        runTest {
            val engine = engine()
            bridge.initializeResult = -4

            engine.start()
            engine.state.value shouldBe TorEngineState.Failed("initialize returned -4")

            bridge.initializeResult = 0
            engine.start()
            engine.state.value shouldBe TorEngineState.Bootstrapping(port = 40_123, progressPermille = 0)
        }

    @Test
    fun `a proxy that cannot bind is a failure, not a running engine`() =
        runTest {
            val engine = engine()
            bridge.socksPort = -5

            engine.start()

            engine.state.value shouldBe TorEngineState.Failed("startSocks returned -5")
            engine.state.value.socksPort shouldBe null
        }

    @Test
    fun `stop closes the proxy but keeps the client alive`() =
        runTest {
            val engine = engine()
            engine.start()

            engine.stop()

            engine.state.value shouldBe TorEngineState.Off
            bridge.calls.last() shouldBe "stopSocks"
            bridge.calls.contains("destroy") shouldBe false
        }

    @Test
    fun `a gentle restart rebuilds the client and keeps the persisted state`() =
        runTest {
            val marker = File(dataDir, "cache/consensus").apply { parentFile.mkdirs(); writeText("cached") }
            val engine = engine()
            engine.start()

            engine.restart(wipeState = false)

            bridge.calls shouldBe listOf(
                "initialize",
                "startSocks(0)",
                "stopSocks",
                "destroy",
                "initialize",
                "startSocks(0)",
            )
            marker.exists() shouldBe true
            engine.state.value shouldBe TorEngineState.Bootstrapping(port = 40_123, progressPermille = 0)
        }

    @Test
    fun `a wiping restart deletes the state before the client is created again`() =
        runTest {
            val marker = File(dataDir, "cache/consensus").apply { parentFile.mkdirs(); writeText("cached") }
            val engine = engine()
            engine.start()
            var presentAtSecondInitialize: Boolean? = null

            bridge.onInitialize = { presentAtSecondInitialize = marker.exists() }
            engine.restart(wipeState = true)

            presentAtSecondInitialize shouldBe false
        }

    @Test
    fun `a wedged guard sample is wiped before the client can read it`() =
        runTest {
            writeGuards(true, true, true)
            var presentAtInitialize: Boolean? = null
            bridge.onInitialize = { presentAtInitialize = File(dataDir, ArtiTorEngine.GUARDS_FILE_PATH).exists() }

            engine().start()

            presentAtInitialize shouldBe false
        }

    @Test
    fun `a healthy guard sample is left alone`() =
        runTest {
            writeGuards(false, false, true)
            var presentAtInitialize: Boolean? = null
            bridge.onInitialize = { presentAtInitialize = File(dataDir, ArtiTorEngine.GUARDS_FILE_PATH).exists() }

            engine().start()

            presentAtInitialize shouldBe true
        }

    @Test
    fun `the native client disappearing is reported as a failure`() =
        runTest {
            val engine = engine()
            engine.start()

            bridge.bootstrapped = -1
            advanceTimeBy(150)

            engine.state.value shouldBe TorEngineState.Failed("the native client disappeared")
        }

    @Test
    fun `hasBootstrappedBefore reads the confirmed guard on disk`() =
        runTest {
            val engine = engine()
            engine.hasBootstrappedBefore() shouldBe false

            writeGuards(false)

            engine.hasBootstrappedBefore() shouldBe true
        }

    @Test
    fun `dormant mode is only forwarded while the engine runs`() =
        runTest {
            val engine = engine()
            engine.setBackgrounded(true)
            bridge.calls shouldBe emptyList()

            engine.start()
            engine.setBackgrounded(true)
            engine.setBackgrounded(false)

            bridge.calls.takeLast(2) shouldBe listOf("setDormant(true)", "setDormant(false)")
        }

    @Test
    fun `health is zero when the engine is not running`() =
        runTest {
            bridge.connectOk = 5
            val engine = engine()

            engine.health() shouldBe TorHealth(0, 0, 0)

            engine.start()
            engine.health() shouldBe TorHealth(connectOk = 5, connectFailed = 0, accessFailedStreak = 0)
        }
}
