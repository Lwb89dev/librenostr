package net.primal.core.networking.tor

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Picks, for each new connection, between going out directly and going through the Tor SOCKS proxy,
 * according to the [RouteConfig] in force at that moment.
 *
 * Asking at connect time is what lets one long-lived client follow a mode switch and an engine
 * restart (the built-in engine picks a new port every time it starts).
 *
 * **A Tor route never falls back to a direct one.** If the built-in engine has no port yet, the
 * answer is still a SOCKS proxy, on a port where nothing listens, so the connection fails. Answering
 * [Proxy.NO_PROXY] there would send the request straight out, the one thing a Tor mode must not do.
 * The destination name is handed to the proxy unresolved (that is how OkHttp uses a SOCKS proxy), so
 * a Tor route makes no local DNS query either.
 *
 * [select] runs on the network threads of the HTTP client, so waiting for a starting engine is
 * acceptable; the wait is bounded by [waitForPortMs] and only taken when [builtInPort] has nothing.
 */
class RouteProxySelector(
    private val config: () -> RouteConfig,
    private val builtInPort: () -> Int?,
    private val awaitBuiltInPort: (timeoutMs: Long) -> Int?,
    private val waitForPortMs: Long = DEFAULT_WAIT_FOR_PORT_MS,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val current = config()
        if (current.routeFor(uri?.host) == Route.DIRECT) return listOf(Proxy.NO_PROXY)
        val port = when (current.engine) {
            TorEngineType.ORBOT -> current.orbotPort
            TorEngineType.BUILT_IN -> builtInPort() ?: awaitBuiltInPort(waitForPortMs)
        }
        return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, port ?: UNROUTABLE_PORT)))
    }

    override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Nothing is expected to listen here; a connection to it is refused at once. */
        const val UNROUTABLE_PORT = 1
        const val DEFAULT_WAIT_FOR_PORT_MS = 20_000L
    }
}
