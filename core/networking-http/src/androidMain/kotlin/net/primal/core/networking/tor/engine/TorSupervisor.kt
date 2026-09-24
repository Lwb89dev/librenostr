package net.primal.core.networking.tor.engine

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides when a [TorEngine] that is not doing its job should be restarted, and how hard.
 *
 * An embedded Tor client fails in ways a plain SOCKS proxy does not, and each one needs a different
 * response. All of these were found the hard way by another app embedding the same client; none of
 * them show up as a crash or an error, only as "Tor never connects":
 *
 * 1. **A start that never finishes.** The directory download stalls, or the client cannot be
 *    created. A fixed timeout is the wrong tool: cold downloads have been measured at anywhere from
 *    12 to 34 seconds on one device. The signal that separates slow from stuck is *forward
 *    progress*, so the watchdog only fires after [Policy.stallAfterMs] with no change.
 * 2. **Stale persisted state.** A guard sample that has gone bad survives restarts and poisons every
 *    start. Before Tor has ever worked here there is nothing on disk worth wiping, so the first
 *    resets are gentle (rebuild the client, keep the cache); after it has worked, or after
 *    [Policy.gentleResetsBeforeWipe] gentle attempts, the state is wiped.
 * 3. **"Ready" but nothing connects.** The client reports it is bootstrapped from a cached directory
 *    while every circuit fails. The native side counts consecutive "could not reach Tor" failures
 *    that no success interrupts; a long enough streak lasting long enough triggers a gentle restart,
 *    which rotates exits. Both thresholds matter: right after a client becomes ready the app dials
 *    everything at once and a burst can fail before the first handshake completes, so a short
 *    window would wipe a healthy client during warm-up.
 * 4. **The network changed.** Circuits built on the old network are dead; a gentle restart rebuilds
 *    them. Rate limited, because networks flap.
 *
 * Every automatic restart is rate limited so a permanently broken network cannot loop the app
 * through restarts.
 *
 * Time is injected ([nowMs]) so the whole policy can be tested with virtual time.
 */
class TorSupervisor(
    private val engine: TorEngine,
    private val scope: CoroutineScope,
    private val hasBootstrappedBefore: () -> Boolean,
    private val nowMs: () -> Long,
    private val policy: Policy = Policy(),
) {

    data class Policy(
        /** How often the state is examined. */
        val tickMs: Long = 5_000L,
        /** No forward progress for this long while trying to connect counts as stuck. */
        val stallAfterMs: Long = 60_000L,
        /** Minimum gap between automatic restarts before Tor has ever worked here. */
        val firstStartCooldownMs: Long = 30_000L,
        /** Minimum gap between automatic restarts once it has worked. */
        val cooldownMs: Long = FIVE_MINUTES_MS,
        /** Gentle restarts tried, with no success in between, before the state is wiped. */
        val gentleResetsBeforeWipe: Int = 3,
        /** Consecutive "could not reach Tor" failures that make a ready client suspect. */
        val deadStreakThreshold: Long = 8L,
        /** How long that streak must last, unbroken, before acting on it. */
        val deadStreakSustainedMs: Long = 60_000L,
        /** Minimum gap between restarts triggered by network changes. */
        val networkChangeCooldownMs: Long = 30_000L,
    )

    private val lock = Mutex()

    private var hasEverBeenReady = false
    private var gentleResets = 0
    private var lastRestartAtMs = NEVER
    private var lastNetworkRestartAtMs = NEVER

    /** What "making progress" looked like at the last change, and when that change happened. */
    private var progressSignature: Any? = null
    private var progressChangedAtMs = 0L

    /** When the current unbroken run of dead-circuit failures began, or [NEVER]. */
    private var deadStreakSinceMs = NEVER

    fun start(): Job {
        hasEverBeenReady = hasBootstrappedBefore()
        return scope.launch {
            while (true) {
                delay(policy.tickMs)
                lock.withLock { evaluate() }
            }
        }
    }

    /** The device's network changed (interface switched, connectivity regained). */
    fun onNetworkChanged() {
        scope.launch {
            lock.withLock {
                val now = nowMs()
                if (!engine.state.value.isRunning) return@withLock
                if (lastNetworkRestartAtMs != NEVER && now - lastNetworkRestartAtMs < policy.networkChangeCooldownMs) {
                    return@withLock
                }
                Napier.i { "Network changed, rebuilding the Tor client" }
                lastNetworkRestartAtMs = now
                restart(now, wipeState = false)
            }
        }
    }

    /** One examination of the engine. Exposed to the tests; production calls it from [start]. */
    internal suspend fun evaluate() {
        val now = nowMs()
        when (val current = engine.state.value) {
            is TorEngineState.Ready -> {
                hasEverBeenReady = true
                gentleResets = 0
                progressSignature = null
                checkForDeadCircuits(now)
            }

            is TorEngineState.Bootstrapping, TorEngineState.Starting -> {
                deadStreakSinceMs = NEVER
                checkForStall(now, current)
            }

            is TorEngineState.Failed -> {
                progressSignature = null
                recover(now, downloading = false, reason = "the start failed: ${current.reason}")
            }

            TorEngineState.Off, TorEngineState.Unavailable -> {
                progressSignature = null
                deadStreakSinceMs = NEVER
            }
        }
    }

    private suspend fun checkForStall(now: Long, current: TorEngineState) {
        val signature = when (current) {
            is TorEngineState.Bootstrapping -> current.progressPermille
            else -> current::class
        }
        if (signature != progressSignature) {
            progressSignature = signature
            progressChangedAtMs = now
            return
        }
        if (now - progressChangedAtMs >= policy.stallAfterMs) {
            recover(
                now = now,
                downloading = current is TorEngineState.Bootstrapping,
                reason = "no progress for ${policy.stallAfterMs} ms",
            )
        }
    }

    private suspend fun checkForDeadCircuits(now: Long) {
        if (engine.health().accessFailedStreak < policy.deadStreakThreshold) {
            deadStreakSinceMs = NEVER
            return
        }
        if (deadStreakSinceMs == NEVER) deadStreakSinceMs = now
        val sustained = now - deadStreakSinceMs >= policy.deadStreakSustainedMs
        if (sustained && !inCooldown(now)) {
            Napier.w { "Tor is ready but nothing connects, rebuilding the client to rotate its circuits" }
            deadStreakSinceMs = NEVER
            restart(now, wipeState = false)
        }
    }

    /**
     * Restarts a stuck engine. The gentle option only rebuilds the client and keeps the cache; the
     * wipe also deletes the guard sample. A stalled *download* always gets the gentle one: it says
     * nothing about persisted state, and wiping would only throw away the partial cache.
     */
    private suspend fun recover(now: Long, downloading: Boolean, reason: String) {
        if (inCooldown(now)) return
        val wipe = !downloading && (hasEverBeenReady || gentleResets >= policy.gentleResetsBeforeWipe)
        Napier.w { "Tor is stuck ($reason), restarting ${if (wipe) "and wiping its state" else "keeping its state"}" }
        if (wipe) gentleResets = 0 else gentleResets++
        restart(now, wipeState = wipe)
    }

    private fun inCooldown(now: Long): Boolean {
        val cooldown = if (hasEverBeenReady) policy.cooldownMs else policy.firstStartCooldownMs
        return lastRestartAtMs != NEVER && now - lastRestartAtMs < cooldown
    }

    private suspend fun restart(now: Long, wipeState: Boolean) {
        lastRestartAtMs = now
        progressSignature = null
        engine.restart(wipeState = wipeState)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val FIVE_MINUTES_MS = 5 * 60_000L
    }
}
