package net.primal.core.networking.tor.engine

import android.content.Context
import io.github.aakira.napier.Napier
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.primal.core.networking.tor.TorEngineType
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.utils.coroutines.createDispatcherProvider

/**
 * The one built-in Tor client of the process, and the way the rest of the app reaches it.
 *
 * It is a process-wide object rather than an injected dependency for the same reason
 * [net.primal.core.networking.tor.TorProxyContextHolder] is: the HTTP clients that need the port are
 * built by plain top-level functions (the Ktor engine factory, the Coil and ExoPlayer clients) that no
 * Hilt scope reaches, and several of them are built before dependency injection is even ready.
 *
 * Nothing is loaded until it is needed. The native library, the engine and its supervisor are created
 * the first time the built-in engine is started, so a user who never enables it pays nothing.
 */
object BuiltInTor {

    private val dispatchers = createDispatcherProvider()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io())
    private val lock = Any()

    private val mutableState = MutableStateFlow<TorEngineState>(TorEngineState.Off)

    /** The state of the built-in client: what the settings screen shows and the proxy selector reads. */
    val state: StateFlow<TorEngineState> = mutableState.asStateFlow()

    private var probed = false
    private var bridge: ArtiBridge? = null
    private var engine: ArtiTorEngine? = null
    private var supervisor: TorSupervisor? = null

    /**
     * Starts the engine if the saved settings ask for it. Called once, early in `Application.onCreate`,
     * before the first HTTP client is built, so the port is usually known by the time it is needed.
     * Reads the settings synchronously, like every other Tor-aware call site at startup.
     */
    fun initialize(context: Context) {
        val settings = TorProxySettingsStore.readBlocking(context)
        if (settings.enabled && settings.engine == TorEngineType.BUILT_IN) startAsync(context)
    }

    /** Whether this build can run the built-in engine at all (the native library is packaged and loads). */
    fun isLibraryAvailable(): Boolean = bridgeOrNull() != null

    /** Starts the engine without blocking the caller. Safe to call when it is already running. */
    fun startAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val created = engineOrCreate(appContext)
            if (created == null) {
                mutableState.value = TorEngineState.Unavailable
                return@launch
            }
            created.start()
        }
    }

    /** Stops the proxy. The client stays alive, so turning it back on is cheap. */
    fun stopAsync() {
        scope.launch { engine?.stop() }
    }

    fun setBackgrounded(backgrounded: Boolean) {
        engine?.setBackgrounded(backgrounded)
    }

    fun onNetworkChanged() {
        supervisor?.onNetworkChanged()
    }

    /** Where to send bytes right now, or null when there is nowhere to send them. */
    fun currentPort(): Int? = mutableState.value.socksPort

    /**
     * Blocks until the proxy has a port, for at most [timeoutMs], and returns it (or null on timeout).
     * Returns null at once when waiting cannot help: the library is missing or the last start failed
     * (the supervisor retries in the background, so the next call may find a port).
     *
     * Blocking is the point: it is called by the HTTP client's connection threads at the moment they
     * need to know where to connect, which turns "Tor is still starting" into a slightly slower first
     * connection instead of a burst of instant failures.
     */
    fun awaitPortBlocking(timeoutMs: Long): Int? {
        val current = mutableState.value
        if (current is TorEngineState.Unavailable || current is TorEngineState.Failed) return null
        return runBlocking {
            withTimeoutOrNull(timeoutMs) { state.mapNotNull { it.socksPort }.first() }
        }
    }

    private fun bridgeOrNull(): ArtiBridge? =
        synchronized(lock) {
            if (!probed) {
                bridge = ArtiLibrary.loadOrNull()
                probed = true
            }
            bridge
        }

    private fun engineOrCreate(context: Context): ArtiTorEngine? =
        synchronized(lock) {
            engine ?: bridgeOrNull()?.let { nativeBridge ->
                ArtiTorEngine(
                    bridge = nativeBridge,
                    dataDir = File(context.filesDir, ARTI_DIR_NAME),
                    scope = scope,
                ).also { created ->
                    engine = created
                    scope.launch { created.state.collect { mutableState.value = it } }
                    supervisor = TorSupervisor(
                        engine = created,
                        scope = scope,
                        hasBootstrappedBefore = created::hasBootstrappedBefore,
                        nowMs = System::currentTimeMillis,
                    ).also { it.start() }
                    Napier.i { "Built-in Tor engine created" }
                }
            }
        }

    private const val ARTI_DIR_NAME = "arti"
}
