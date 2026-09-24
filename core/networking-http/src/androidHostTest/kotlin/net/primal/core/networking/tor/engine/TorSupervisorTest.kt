package net.primal.core.networking.tor.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class TorSupervisorTest {

    private val policy = TorSupervisor.Policy(
        tickMs = 1_000,
        stallAfterMs = 60_000,
        firstStartCooldownMs = 30_000,
        cooldownMs = 300_000,
        gentleResetsBeforeWipe = 3,
        deadStreakThreshold = 8,
        deadStreakSustainedMs = 60_000,
        networkChangeCooldownMs = 30_000,
    )

    private fun TestScope.supervisor(engine: TorEngine, workedBefore: Boolean = false) =
        TorSupervisor(
            engine = engine,
            scope = backgroundScope,
            hasBootstrappedBefore = { workedBefore },
            nowMs = { testScheduler.currentTime },
            policy = policy,
        ).also { it.start() }

    /** Lets virtual time pass in tick-sized steps so the supervisor's own loop runs. */
    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a download that keeps making progress is never restarted`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Bootstrapping(port = 1, progressPermille = 0))
            supervisor(engine)

            repeat(10) { step ->
                pass(30_000)
                engine.set(TorEngineState.Bootstrapping(port = 1, progressPermille = (step + 1) * 50))
            }

            engine.restarts shouldBe emptyList()
        }

    @Test
    fun `a stalled download gets a gentle restart, never a wipe`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Bootstrapping(port = 1, progressPermille = 300))
            supervisor(engine, workedBefore = true)

            pass(65_000)

            // Even though Tor worked here before: a stuck download says nothing about persisted state.
            engine.restarts shouldBe listOf(false)
        }

    @Test
    fun `a start that never produces a client escalates from gentle restarts to a wipe`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Failed("initialize returned -4"))
            engine.stateAfterRestart = TorEngineState.Failed("initialize returned -4")
            supervisor(engine)

            // First-start cooldown is 30 s: attempts at ~1, 31, 61, 91 s.
            pass(100_000)

            engine.restarts shouldBe listOf(false, false, false, true)
        }

    @Test
    fun `when Tor worked here before, a stuck start wipes at once`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Failed("client disappeared"))
            supervisor(engine, workedBefore = true)

            pass(5_000)

            engine.restarts shouldBe listOf(true)
        }

    @Test
    fun `restarts are rate limited`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Failed("boom"))
            engine.stateAfterRestart = TorEngineState.Failed("boom")
            supervisor(engine, workedBefore = true)

            pass(200_000)

            // Cooldown after Tor has worked is 5 minutes, so a permanently broken network cannot loop.
            engine.restarts.size shouldBe 1
        }

    @Test
    fun `a ready client with failing connections is rebuilt only after the streak is sustained`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Ready(port = 1))
            engine.health = TorHealth(connectOk = 0, connectFailed = 20, accessFailedStreak = 20)
            supervisor(engine, workedBefore = true)

            // Warm-up grace: the app dials everything at once and a burst can fail before the first
            // handshake completes, so a short streak must not be acted on.
            pass(30_000)
            engine.restarts shouldBe emptyList()

            pass(45_000)
            engine.restarts shouldBe listOf(false)
        }

    @Test
    fun `one success in the middle of a streak cancels it`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Ready(port = 1))
            engine.health = TorHealth(0, 20, accessFailedStreak = 20)
            supervisor(engine, workedBefore = true)

            pass(50_000)
            engine.health = TorHealth(1, 20, accessFailedStreak = 0) // a stream connected: circuits work
            pass(50_000)
            engine.health = TorHealth(1, 40, accessFailedStreak = 20)
            pass(50_000)

            engine.restarts shouldBe emptyList()
        }

    @Test
    fun `a short streak of failures is not a dead client`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Ready(port = 1))
            engine.health = TorHealth(0, 3, accessFailedStreak = 3)
            supervisor(engine, workedBefore = true)

            pass(300_000)

            engine.restarts shouldBe emptyList()
        }

    @Test
    fun `a network change rebuilds the client once, then waits out the cooldown`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Ready(port = 1))
            val supervisor = supervisor(engine, workedBefore = true)

            supervisor.onNetworkChanged()
            runCurrent()
            engine.set(TorEngineState.Ready(port = 2))
            supervisor.onNetworkChanged()
            runCurrent()

            engine.restarts shouldBe listOf(false)

            pass(31_000)
            engine.set(TorEngineState.Ready(port = 3))
            supervisor.onNetworkChanged()
            runCurrent()

            engine.restarts shouldBe listOf(false, false)
        }

    @Test
    fun `a network change while Tor is off does nothing`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Off)
            val supervisor = supervisor(engine)

            supervisor.onNetworkChanged()
            runCurrent()

            engine.restarts shouldBe emptyList()
        }

    @Test
    fun `an unavailable engine is left alone`() =
        runTest {
            val engine = FakeTorEngine(TorEngineState.Unavailable)
            supervisor(engine)

            pass(600_000)

            engine.restarts shouldBe emptyList()
        }
}
