package net.primal.core.networking.tor.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * A Tor client running inside the app, exposed as a loopback SOCKS5 proxy.
 *
 * The only implementation is [ArtiTorEngine]; the interface exists so the policy on top of it
 * ([TorSupervisor]) and the proxy selector that consumes it can be tested without any native code.
 */
interface TorEngine {

    val state: StateFlow<TorEngineState>

    /**
     * Starts the client and the proxy if they are not already running. Returns when the proxy is
     * bound or the start failed.
     */
    suspend fun start()

    /**
     * Stops the proxy. The client stays alive, so stopping and starting never contends for Arti's
     * state-file lock.
     */
    suspend fun stop()

    /**
     * Drops the client and starts a fresh one. With [wipeState] the persisted guard sample and
     * directory cache are deleted first, which is the recovery for a sample that has gone bad;
     * without it only the in-memory client is rebuilt (new circuits, new exits) and the warm cache
     * makes the restart cheap.
     */
    suspend fun restart(wipeState: Boolean)

    /** Tells the client whether the app is in the background, so it can suspend its background work. */
    fun setBackgrounded(backgrounded: Boolean)

    /** Connect outcomes counted since the client was created; all zero when it is not running. */
    fun health(): TorHealth
}
