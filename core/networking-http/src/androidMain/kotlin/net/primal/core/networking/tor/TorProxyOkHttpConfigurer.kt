package net.primal.core.networking.tor

import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import okhttp3.OkHttpClient

private const val TOR_SOCKS_HOST = "127.0.0.1"

// OkHttp's implicit default timeout is 10s; Tor/Orbot adds real latency on top of that, so
// timeouts are widened whenever the proxy is active. 30s matches the "measured sweet spot"
// Amethyst documents for its own Orbot-SOCKS relay client — generous enough for circuit
// building without leaving a hung connection in the UI for a full minute or more.
private const val TOR_CONNECT_TIMEOUT_SECONDS = 30L
private const val TOR_READ_TIMEOUT_SECONDS = 30L
private const val TOR_WRITE_TIMEOUT_SECONDS = 30L

/**
 * Applies Orbot's SOCKS proxy and Tor-appropriate timeouts when [settings] has Tor enabled;
 * a no-op otherwise. Deliberately has no fallback to a direct connection if the proxy port
 * isn't reachable — the call should fail, not silently leak traffic outside Tor.
 */
fun OkHttpClient.Builder.applyTorProxyIfEnabled(settings: TorProxySettings): OkHttpClient.Builder =
    apply {
        if (!settings.enabled) return@apply
        proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(TOR_SOCKS_HOST, settings.socksPort)))
        connectTimeout(TOR_CONNECT_TIMEOUT_SECONDS.seconds.toJavaDuration())
        readTimeout(TOR_READ_TIMEOUT_SECONDS.seconds.toJavaDuration())
        writeTimeout(TOR_WRITE_TIMEOUT_SECONDS.seconds.toJavaDuration())
    }
