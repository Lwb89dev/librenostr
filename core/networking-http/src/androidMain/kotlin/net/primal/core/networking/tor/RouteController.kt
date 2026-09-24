package net.primal.core.networking.tor

import java.io.IOException
import java.net.Socket
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import okhttp3.ConnectionPool

/**
 * A route configuration and how many times it has changed. [epoch] exists so that things holding
 * long-lived connections (the relay sockets) can tell "the route changed, reconnect" from "the same
 * route was written again" without comparing configurations themselves.
 */
data class RouteState(val config: RouteConfig, val epoch: Long)

/**
 * The single, live answer to "how does traffic leave the device right now", and the place that
 * reacts when the answer changes.
 *
 * HTTP clients are built once and live for the whole process, so the route cannot be baked into them
 * the way the old "restart the app for Tor to take effect" behavior did: every client asks this
 * object on every new connection instead. A switch then takes effect for the next connection with no
 * restart, which matters for a privacy switch: a user who turns Tor on must not keep browsing directly
 * until they happen to restart the app.
 *
 * A switch has to reach connections that already exist, too, and there are three kinds:
 *
 * - Idle pooled ones are closed here.
 * - Ones in use, and WebSockets, which can stay up for days and are never idle, are dropped here by
 *   closing every socket the app opened directly (see [TrackingSocketFactory]). A relay connection
 *   opened directly must not keep carrying traffic after Tor was chosen. Closing at the transport
 *   covers every WebSocket user (relay pools, the remote signer, anything added later) without each of
 *   them watching [state]; they see an ordinary disconnect and reconnect by the new route. Requests in
 *   flight fail and are retried by their callers, which is what a network change looks like anyway.
 * - A pooled connection that somehow outlives all that is refused when next touched by
 *   [RouteGuardInterceptor].
 */
class RouteController(initial: RouteConfig) {

    private val mutableState = MutableStateFlow(RouteState(config = initial, epoch = 0))

    val state: StateFlow<RouteState> = mutableState.asStateFlow()

    val config: RouteConfig get() = mutableState.value.config

    private val pools: MutableSet<ConnectionPool> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val directSockets: MutableSet<Socket> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Registers a client's pool so a route change can close its idle connections. Weakly held. */
    fun track(pool: ConnectionPool) {
        pools.add(pool)
    }

    /** Registers a socket opened directly so a route change can close it. Weakly held. */
    fun trackDirectSocket(socket: Socket) {
        directSockets.add(socket)
    }

    /** Returns whether [newConfig] differs from the current one. Writing the same value again is a no-op. */
    fun update(newConfig: RouteConfig): Boolean {
        val previous = mutableState.getAndUpdate { current ->
            if (current.config == newConfig) current else RouteState(config = newConfig, epoch = current.epoch + 1)
        }
        val changed = previous.config != newConfig
        if (changed) {
            evictIdleConnections()
            closeDirectSockets()
        }
        return changed
    }

    private fun evictIdleConnections() {
        val snapshot = synchronized(pools) { pools.toList() }
        snapshot.forEach { it.evictAll() }
    }

    private fun closeDirectSockets() {
        val snapshot = synchronized(directSockets) { directSockets.toList().also { directSockets.clear() } }
        snapshot.forEach { socket ->
            try {
                socket.close()
            } catch (ignored: IOException) {
                // Already closed: exactly the state this is trying to reach.
            }
        }
    }
}
