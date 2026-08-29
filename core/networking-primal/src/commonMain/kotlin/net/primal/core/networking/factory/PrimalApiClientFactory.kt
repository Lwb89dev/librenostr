package net.primal.core.networking.factory

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import net.primal.core.config.AppConfigFactory
import net.primal.core.networking.primal.PrimalApiClient
import net.primal.core.networking.primal.ProxyPrimalApiClient
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.domain.global.PrimalServerType

private const val MAX_WEBSOCKET_FRAME_BYTES = 256L * 1024

internal val defaultSocketsHttpClient by lazy {
    HttpClientFactory.createHttpClientWithDefaultConfig {
        install(WebSockets) {
            maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
        }
    }
}

internal val keepAliveSocketsHttpClient by lazy {
    HttpClientFactory.createHttpClientWithDefaultConfig {
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
        }
    }
}

object PrimalApiClientFactory {

    private val clients: MutableMap<PrimalServerType, PrimalApiClient> = mutableMapOf()

    fun getDefault(serverType: PrimalServerType): PrimalApiClient {
        return clients.getOrPut(serverType) {
            create(serverType = serverType)
        }
    }

    fun create(serverType: PrimalServerType): PrimalApiClient {
        return create(
            dispatcherProvider = createDispatcherProvider(),
            httpClient = keepAliveSocketsHttpClient,
            serverType = serverType,
        )
    }

    fun create(
        dispatcherProvider: DispatcherProvider,
        serverType: PrimalServerType,
        httpClient: HttpClient,
    ): PrimalApiClient {
        return ProxyPrimalApiClient(
            dispatcherProvider = dispatcherProvider,
            httpClient = httpClient,
            serverType = serverType,
            appConfigProvider = AppConfigFactory.createAppConfigProvider(),
            appConfigHandler = AppConfigFactory.createAppConfigHandler(),
        )
    }

    fun pauseAll() {
        clients.values.forEach { (it as? ProxyPrimalApiClient)?.pause() }
    }

    fun resumeAll() {
        clients.values.forEach { (it as? ProxyPrimalApiClient)?.resume() }
    }
}
