package net.primal.core.networking.tor

import net.primal.core.networking.tor.engine.BuiltInTor
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

private fun defaultRouteRuntime() =
    RouteRuntime(
        controller = NetworkRoute.controller,
        builtInPort = BuiltInTor::currentPort,
        awaitBuiltInPort = BuiltInTor::awaitPortBlocking,
    )

/**
 * Makes this client follow the app's network mode: direct, everything through Tor, or only `.onion`
 * through Tor. Every OkHttp client in the app goes through this, so there is exactly one place that
 * decides how a connection leaves the device.
 *
 * The mode is read on every connection rather than once when the client is built, so switching it
 * takes effect without restarting the app. See [RouteController], [RouteProxySelector], [RouteDns] and
 * the two interceptors for what each part guarantees.
 */
fun OkHttpClient.Builder.applyNetworkRoute(): OkHttpClient.Builder = applyNetworkRoute(defaultRouteRuntime())

fun OkHttpClient.Builder.applyNetworkRoute(runtime: RouteRuntime): OkHttpClient.Builder =
    apply {
        val controller = runtime.controller
        val config = { controller.config }
        connectionPool(ConnectionPool().also(controller::track))
        socketFactory(TrackingSocketFactory(onCreated = controller::trackDirectSocket))
        proxySelector(RouteProxySelector(config, runtime.builtInPort, runtime.awaitBuiltInPort))
        dns(RouteDns(config))
        addInterceptor(RouteTimeoutInterceptor(config))
        addNetworkInterceptor(RouteGuardInterceptor(config))
    }
