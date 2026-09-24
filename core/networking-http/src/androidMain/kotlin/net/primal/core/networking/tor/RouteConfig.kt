package net.primal.core.networking.tor

/** Which way one connection leaves the device. */
enum class Route { DIRECT, TOR }

/**
 * Everything that decides where a connection goes, in one immutable value, so a decision is always
 * made from a single consistent snapshot rather than from several settings read at different moments.
 *
 * [orbotPort] is only meaningful for [TorEngineType.ORBOT]; the built-in engine picks its own port.
 */
data class RouteConfig(
    val mode: NetworkMode,
    val engine: TorEngineType,
    val orbotPort: Int,
) {

    /**
     * The route for a connection to [host].
     *
     * A host that cannot be read (`null`) is treated as one that must not be trusted with a direct
     * connection: only [NetworkMode.DIRECT] lets it out directly. In [NetworkMode.ONION_ONLY] that
     * costs a clearnet connection some latency in a case that should not occur; the opposite guess
     * would send a possible onion name to a DNS resolver.
     */
    fun routeFor(host: String?): Route =
        when (mode) {
            NetworkMode.DIRECT -> Route.DIRECT
            NetworkMode.TOR -> Route.TOR
            NetworkMode.ONION_ONLY -> if (host != null && !host.isOnionHost()) Route.DIRECT else Route.TOR
        }
}

fun TorProxySettings.toRouteConfig() = RouteConfig(mode = effectiveMode, engine = engine, orbotPort = socksPort)
