package net.primal.core.networking.tor

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * The only DNS resolver the HTTP clients are allowed to use, and it refuses whatever would leak.
 *
 * OkHttp asks its [Dns] only for connections that go out *directly*: over a SOCKS proxy the name is
 * sent to the proxy unresolved. So a lookup reaching this class means "this name is about to be
 * resolved by the device's resolver", which is exactly what must never happen in two cases:
 *
 * - **Tor mode.** Nothing is resolved locally. A lookup here means a connection is trying to bypass
 *   the proxy (for instance one that was chosen just before a mode switch), so it fails.
 * - **An onion name in any mode.** A `.onion` name cannot be resolved by any resolver but Tor, so
 *   sending it to one only tells the network operator which onion service the user asked for.
 */
class RouteDns(
    private val config: () -> RouteConfig,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (config().mode == NetworkMode.TOR) {
            throw UnknownHostException("Refusing to resolve $hostname locally: Tor mode sends names to Tor unresolved")
        }
        if (hostname.isOnionHost()) {
            throw UnknownHostException("Refusing to resolve an onion name outside Tor")
        }
        return delegate.lookup(hostname)
    }
}
