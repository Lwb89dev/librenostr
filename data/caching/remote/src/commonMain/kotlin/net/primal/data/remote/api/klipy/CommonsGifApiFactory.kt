package net.primal.data.remote.api.klipy

import net.primal.core.networking.factory.HttpClientFactory

/** Builds the Wikimedia Commons GIF client without involving Primal services. */
object CommonsGifApiFactory {
    private val defaultHttpClient = HttpClientFactory.createHttpClientWithDefaultConfig()

    fun create(): KlipyApi = KlipyApiImpl(httpClient = defaultHttpClient)
}
