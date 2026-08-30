package net.primal.core.networking.factory

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import net.primal.core.networking.primal.PrimalApiClient
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.global.PrimalServerType

internal val defaultSocketsHttpClient by lazy {
    HttpClientFactory.createHttpClientWithDefaultConfig {
        install(WebSockets)
    }
}

internal val keepAliveSocketsHttpClient by lazy {
    HttpClientFactory.createHttpClientWithDefaultConfig {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }
}

object PrimalApiClientFactory {

    /** Legacy centralized cache transport; deliberately fail-closed. */
    fun getDefault(serverType: PrimalServerType): PrimalApiClient = unavailable(serverType)

    fun create(serverType: PrimalServerType): PrimalApiClient = unavailable(serverType)

    fun create(
        dispatcherProvider: net.primal.core.utils.coroutines.DispatcherProvider,
        serverType: PrimalServerType,
        httpClient: HttpClient,
    ): PrimalApiClient = unavailable(serverType)

    fun pauseAll() = Unit

    fun resumeAll() = Unit

    private fun unavailable(serverType: PrimalServerType): Nothing = throw NetworkException(
        "Centralized API clients are disabled for LibreNostr (${serverType.name}). Use RelayPool.",
    )
}
