package net.primal.core.networking.tor

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The process-wide [RouteController], initialized from the saved settings on first use.
 *
 * Lazy for the same reason the rest of the Tor wiring reads its settings synchronously: the HTTP
 * clients that consult it are built by plain top-level functions before dependency injection exists,
 * so the saved mode has to be readable at the moment the first client is created.
 */
object NetworkRoute {

    val controller: RouteController by lazy {
        val saved = TorProxySettingsStore.readBlocking(TorProxyContextHolder.require())
        RouteController(initial = saved.toRouteConfig())
    }

    /**
     * Whether a component that does its own networking, outside the app's HTTP clients, may fetch
     * [url] directly under the current mode. Android's media metadata reader is one: it takes a URL and
     * opens its own connection, so it cannot be pointed at Tor and has to be skipped whenever the URL
     * is meant to go through it. An address that cannot be read is never fetched directly.
     */
    fun canFetchDirectly(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return controller.config.routeFor(host) == Route.DIRECT
    }
}
