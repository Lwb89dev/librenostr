package net.primal.core.networking.tor.engine

/**
 * Two independent facts about the built-in Tor client that used to coincide and no longer do, which
 * is why "running" is more than one state.
 *
 * The SOCKS listener binds within about 130 ms, but the directory it needs before it can build a
 * circuit takes from a few seconds (warm cache) to over half a minute (cold). Under the client's
 * on-demand bootstrap the proxy is usable immediately and each connection waits for its own
 * circuit, so the two facts diverge for that whole window and callers want different ones:
 *
 * - read [socksPort] to decide where to send bytes: dials issued while [Bootstrapping] are queued
 *   behind the download, not lost, whereas treating it as "no proxy" makes every dial fail;
 * - read [isReady] to tell the user (or a watchdog) whether Tor is actually working.
 */
sealed interface TorEngineState {

    /** The native library is not part of this build or cannot load. Nothing can start. */
    data object Unavailable : TorEngineState

    /** Not running. */
    data object Off : TorEngineState

    /** The client is being created; nothing is routable yet. */
    data object Starting : TorEngineState

    /** The proxy is bound and routable; the directory is still downloading. */
    data class Bootstrapping(val port: Int, val progressPermille: Int) : TorEngineState

    /** The proxy is bound and circuits can be built now. */
    data class Ready(val port: Int) : TorEngineState

    /** The last attempt to start failed. [reason] is for diagnostics, not for users. */
    data class Failed(val reason: String) : TorEngineState

    /** Where to send bytes, or null when there is nowhere to send them. */
    val socksPort: Int?
        get() = when (this) {
            is Bootstrapping -> port
            is Ready -> port
            else -> null
        }

    val isReady: Boolean get() = this is Ready

    /** True from the moment a start is under way until it is stopped or fails. */
    val isRunning: Boolean get() = this is Starting || this is Bootstrapping || this is Ready

    /** True while the engine is running but Tor cannot yet carry traffic. */
    val isTryingToConnect: Boolean get() = this is Starting || this is Bootstrapping
}
