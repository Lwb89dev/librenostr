package net.primal.core.networking.tor

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Sends every connection through a Tor SOCKS proxy whose port is only known while the app runs.
 *
 * The built-in engine picks an ephemeral port and picks a new one every time it restarts, so the
 * port cannot be baked into a client when it is built the way Orbot's fixed one is. Asking at connect
 * time means one long-lived client keeps working across engine restarts.
 *
 * **It never answers "no proxy".** If no port is available, [select] still returns a SOCKS proxy, on
 * a port where nothing listens, so the connection fails. Answering [Proxy.NO_PROXY] there would send
 * the request straight out, which is the one thing a Tor mode must not do. (The same rule the Orbot
 * configuration follows: if the proxy is not reachable, the call fails.)
 *
 * [select] runs on the network threads of the HTTP client, so waiting here is acceptable; the wait is
 * bounded by [waitForPortMs] and only taken when [currentPort] has nothing yet.
 */
class TorPortProxySelector(
    private val currentPort: () -> Int?,
    private val awaitPort: (timeoutMs: Long) -> Int?,
    private val waitForPortMs: Long = DEFAULT_WAIT_FOR_PORT_MS,
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val port = currentPort() ?: awaitPort(waitForPortMs)
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
