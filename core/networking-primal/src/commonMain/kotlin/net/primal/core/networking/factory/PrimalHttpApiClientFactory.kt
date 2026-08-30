package net.primal.core.networking.factory

import io.ktor.client.HttpClient
import net.primal.core.networking.primal.PrimalHttpApiClient
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.global.PrimalServerType

object PrimalHttpApiClientFactory {

    /** Legacy centralized HTTP transport; deliberately fail-closed. */
    fun getDefault(serverType: PrimalServerType): PrimalHttpApiClient = unavailable(serverType)

    fun create(serverType: PrimalServerType): PrimalHttpApiClient = unavailable(serverType)

    fun create(serverType: PrimalServerType, httpClient: HttpClient): PrimalHttpApiClient =
        unavailable(serverType)

    private fun unavailable(serverType: PrimalServerType): Nothing = throw NetworkException(
        "Centralized HTTP API clients are disabled for LibreNostr (${serverType.name}). Use RelayPool.",
    )
}
