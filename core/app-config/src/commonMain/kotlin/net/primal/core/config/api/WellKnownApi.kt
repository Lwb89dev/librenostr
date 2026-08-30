package net.primal.core.config.api

import de.jensklingenberg.ktorfit.http.GET

internal interface WellKnownApi {
    @GET("https://nostrich.org/.well-known/librenostr-endpoints.json")
    suspend fun fetchApiConfig(): ApiConfigResponse
}
