package net.primal.core.networking.tor

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * A socket factory that reports every socket it creates, so they can be closed when the network mode
 * changes.
 *
 * OkHttp uses the client's socket factory for every connection that leaves the device directly, and
 * for those only: a connection through a SOCKS proxy is opened by the proxy machinery instead. That
 * is exactly the set that has to be dropped when Tor is chosen. It is the one hook that also sees
 * WebSockets, because OkHttp runs neither network interceptors nor event listeners for those calls.
 */
class TrackingSocketFactory(
    private val delegate: SocketFactory = getDefault(),
    private val onCreated: (Socket) -> Unit,
) : SocketFactory() {

    override fun createSocket(): Socket = delegate.createSocket().also(onCreated)

    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port).also(onCreated)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).also(onCreated)

    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port).also(onCreated)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).also(onCreated)
}
