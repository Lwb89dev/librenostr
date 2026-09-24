package net.primal.core.networking.tor

import java.io.IOException
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

// OkHttp's implicit default timeout is 10s; Tor adds real latency on top of that (circuit building
// alone can take several seconds), so timeouts are widened on connections that go through Tor. 30s is
// the value Amethyst documents as its measured sweet spot for the same SOCKS setup: generous enough
// for a circuit to build without leaving a hung connection in the UI for a minute or more.
internal const val TOR_TIMEOUT_MS = 30_000

/**
 * Widens the timeouts of calls that will go through Tor, and leaves direct ones exactly as they were.
 *
 * Applying the wide values to the whole client would make a direct connection wait 30 seconds for a
 * dead host, a regression for everyone who does not use Tor. Only ever raises a timeout: a caller that
 * asked for longer (Ktor's per-request timeouts) keeps it.
 */
class RouteTimeoutInterceptor(private val config: () -> RouteConfig) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (config().routeFor(request.url.host) == Route.DIRECT) return chain.proceed(request)

        return chain
            .withConnectTimeout(maxOf(chain.connectTimeoutMillis(), TOR_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .withReadTimeout(maxOf(chain.readTimeoutMillis(), TOR_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .withWriteTimeout(maxOf(chain.writeTimeoutMillis(), TOR_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .proceed(request)
    }
}

/**
 * A network interceptor that refuses to use a connection whose route no longer matches the mode.
 *
 * The proxy selector decides for *new* connections, but OkHttp also reuses pooled ones, and a pooled
 * connection remembers only its host, not why it was opened. After a switch from direct to Tor, a
 * still-open direct connection to the same host would otherwise be picked up and carry Tor-mode
 * traffic outside Tor. Closing the idle connections at switch time handles most of it; a connection
 * that was busy at that instant returns to the pool afterwards, and this is what catches it.
 *
 * It fails the call instead of quietly carrying on, and closes the offending connection so nothing
 * reuses it again. That costs one failed request in a rare moment; the alternative is a leak.
 *
 * OkHttp does not run network interceptors for WebSocket calls, so this does not see those; they
 * are covered by closing every direct socket on a switch (see [RouteController]).
 */
class RouteGuardInterceptor(private val config: () -> RouteConfig) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connection = chain.connection()
        val allowed = connection == null ||
            connection.route().proxy.isForRoute(config().routeFor(request.url.host))
        if (allowed) return chain.proceed(request)

        closeQuietly(connection.socket())
        throw IOException("Connection to ${request.url.host} was opened for a different network mode")
    }

    private fun Proxy.isForRoute(route: Route): Boolean =
        when (route) {
            Route.DIRECT -> type() == Proxy.Type.DIRECT
            Route.TOR -> type() == Proxy.Type.SOCKS
        }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (ignored: IOException) {
            // Already closed, or closing; either way it will not be reused.
        }
    }
}
