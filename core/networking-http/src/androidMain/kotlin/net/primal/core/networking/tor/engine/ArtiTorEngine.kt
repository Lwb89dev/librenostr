package net.primal.core.networking.tor.engine

import io.github.aakira.napier.Napier
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs Arti through [ArtiBridge] and turns its blocking, polled surface into a [TorEngineState].
 *
 * **One lifecycle lock.** The native client is a process-wide singleton and `initialize`/`destroy`
 * are blocking calls that ignore coroutine cancellation. Without a lock, a restart that calls
 * `destroy()` while a start is still inside `initialize()` tears the client down moments after it
 * comes up and leaves a listener with nothing behind it. Every transition therefore takes
 * [lifecycle], and a restart simply waits for an in-flight start to finish before tearing it down.
 *
 * **Readiness is polled.** The native side reports live readiness and download progress; driving the
 * state off log lines is what raced (a "bootstrapped" line arriving before the proxy was recorded as
 * bound, and the transition being dropped). The watcher below asks every [pollIntervalMs].
 *
 * **State is never wiped on an ordinary start.** The directory cache is what turns a cold start of
 * tens of seconds into a warm one of a few, so it survives restarts; only [restart] with
 * `wipeState`, or a guard sample judged wedged at start, deletes it.
 *
 * All native calls run on [dispatcher], never on the caller's thread.
 */
class ArtiTorEngine(
    private val bridge: ArtiBridge,
    private val dataDir: File,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = POLL_WHILE_STARTING_MS,
) : TorEngine {

    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow<TorEngineState>(TorEngineState.Off)
    override val state: StateFlow<TorEngineState> = mutableState.asStateFlow()

    private var watcher: Job? = null

    override suspend fun start() {
        lifecycle.withLock { startLocked() }
    }

    override suspend fun stop() {
        lifecycle.withLock {
            withContext(dispatcher) {
                watcher?.cancel()
                watcher = null
                if (mutableState.value.isRunning) bridge.stopSocks()
                mutableState.value = TorEngineState.Off
            }
        }
    }

    override suspend fun restart(wipeState: Boolean) {
        lifecycle.withLock {
            withContext(dispatcher) {
                watcher?.cancel()
                watcher = null
                bridge.stopSocks()
                bridge.destroy()
                if (wipeState) wipeDataDir("requested")
                mutableState.value = TorEngineState.Off
                startLocked()
            }
        }
    }

    override fun setBackgrounded(backgrounded: Boolean) {
        if (mutableState.value.isRunning) bridge.setDormant(soft = backgrounded)
    }

    override fun health(): TorHealth =
        if (mutableState.value.isRunning) bridge.health() else TorHealth(0, 0, 0)

    /** Callers hold [lifecycle]. */
    private suspend fun startLocked() =
        withContext(dispatcher) {
            if (mutableState.value.isRunning) return@withContext
            mutableState.value = TorEngineState.Starting

            dropWedgedGuardSample()

            val initResult = bridge.initialize(dataDir.absolutePath)
            if (initResult != 0) {
                fail("initialize returned $initResult")
                return@withContext
            }
            val port = bridge.startSocks(port = 0)
            if (port <= 0) {
                fail("startSocks returned $port")
                return@withContext
            }

            mutableState.value = TorEngineState.Bootstrapping(
                port = port,
                progressPermille = bridge.bootstrapProgress().coerceAtLeast(0),
            )
            Napier.i { "Built-in Tor proxy listening on 127.0.0.1:$port (directory downloading)" }
            watch(port)
        }

    private fun fail(reason: String) {
        Napier.w { "Built-in Tor failed to start: $reason" }
        drainNativeLog()
        mutableState.value = TorEngineState.Failed(reason)
    }

    /**
     * Follows the client until it stops being ours. Publishes progress while the directory downloads,
     * promotes to [TorEngineState.Ready] when Arti says circuits can be built, and reports the client
     * disappearing (a native reset behind our back) as a failure the supervisor can recover from.
     */
    private fun watch(port: Int) {
        watcher?.cancel()
        watcher = scope.launch(dispatcher) {
            while (isActive) {
                drainNativeLog()
                when (bridge.isBootstrapped()) {
                    1 -> {
                        if (mutableState.value !is TorEngineState.Ready) {
                            Napier.i { "Built-in Tor is ready" }
                            mutableState.value = TorEngineState.Ready(port)
                        }
                        delay(POLL_WHEN_READY_MS)
                    }
                    -1 -> {
                        mutableState.value = TorEngineState.Failed("the native client disappeared")
                        return@launch
                    }
                    else -> {
                        val progress = bridge.bootstrapProgress().coerceAtLeast(0)
                        val current = mutableState.value
                        if (current is TorEngineState.Bootstrapping && current.progressPermille != progress) {
                            mutableState.value = current.copy(progressPermille = progress)
                        }
                        delay(pollIntervalMs)
                    }
                }
            }
        }
    }

    /** Forwards the native log queue to the app log. Cheap: an empty queue returns null. */
    private fun drainNativeLog() {
        bridge.pollLog()?.lineSequence()?.forEach { line -> Napier.d { "arti: $line" } }
    }

    /**
     * Deletes the persisted state when the guard sample on disk is wedged (see [ArtiGuardState]).
     * Run before the client is created because the file is read by Arti at that moment, and it would
     * otherwise come up already broken and stay that way across restarts.
     */
    private fun dropWedgedGuardSample() {
        val file = File(dataDir, GUARDS_FILE_PATH)
        if (!file.exists()) return
        val root = ArtiGuardState.parseOrNull(file.readText()) ?: return
        if (ArtiGuardState.hasNoUsableGuards(root)) wipeDataDir("no usable guards left in the persisted sample")
    }

    private fun wipeDataDir(reason: String) {
        Napier.w { "Wiping built-in Tor state: $reason" }
        dataDir.deleteRecursively()
    }

    /** Whether the persisted guard sample proves Tor worked on this install before. */
    fun hasBootstrappedBefore(): Boolean {
        val file = File(dataDir, GUARDS_FILE_PATH)
        if (!file.exists()) return false
        return ArtiGuardState.parseOrNull(file.readText())?.let(ArtiGuardState::hasConfirmedGuard) ?: false
    }

    companion object {
        /** Arti nests its own `state` directory inside the state directory the wrapper passes. */
        const val GUARDS_FILE_PATH = "state/state/guards.json"

        const val POLL_WHILE_STARTING_MS = 500L
        const val POLL_WHEN_READY_MS = 5_000L
    }
}
