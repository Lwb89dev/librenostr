package net.primal.core.networking.tor.engine

import android.content.Context
import io.github.aakira.napier.Napier
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.primal.core.networking.tor.NetworkRoute
import net.primal.core.networking.tor.TorProxySettings
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.networking.tor.toRouteConfig
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

    /**
     * Start/stop requests, run one at a time and in order by the loop in [initialize]. A conflated
     * channel keeps only the latest: with several requests racing on separate coroutines, "on" then
     * "off" could finish as "off" then "on", leaving the engine running while the mode says direct.
     */
    private val engineCommands = Channel<Boolean>(Channel.CONFLATED)

    private var probed = false
    private var bridge: ArtiBridge? = null
    private var engine: ArtiTorEngine? = null
    private var supervisor: TorSupervisor? = null

    /**
     * Puts the saved network mode in force and keeps it in force as the settings change. Called once,
     * early in `Application.onCreate`, before the first HTTP client is built, so the mode and the
     * engine's port are usually known by the time the first connection needs them. The first read is
     * synchronous, like every other Tor-aware call site at startup.
     *
     * This is the one place where a settings change becomes behavior: it updates the route every
     * client consults and starts or stops the engine. Nothing else starts or stops the engine, so what
     * the settings say and what the app does cannot drift apart.
     */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        scope.launch { for (wanted in engineCommands) runEngineCommand(wanted, appContext) }
        apply(TorProxySettingsStore.readBlocking(appContext))
        scope.launch { TorProxySettingsStore.dataStore(appContext).data.collect(::apply) }
    }

    private fun apply(settings: TorProxySettings) {
        NetworkRoute.controller.update(settings.toRouteConfig())
        engineCommands.trySend(settings.wantsBuiltInEngine)
    }

    private suspend fun runEngineCommand(wanted: Boolean, context: Context) {
        if (!wanted) {
            engine?.stop()
            return
        }
        val created = engineOrCreate(context)
        if (created == null) {
            mutableState.value = TorEngineState.Unavailable
            return
        }
        created.start()
    }

    /** Whether this build can run the built-in engine at all (the native library is packaged and loads). */
    fun isLibraryAvailable(): Boolean = bridgeOrNull() != null

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
