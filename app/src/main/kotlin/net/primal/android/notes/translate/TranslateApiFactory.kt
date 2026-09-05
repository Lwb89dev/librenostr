package net.primal.android.notes.translate

import net.primal.core.networking.factory.HttpClientFactory

object TranslateApiFactory {

    private val defaultHttpClient by lazy { HttpClientFactory.createHttpClientWithDefaultConfig() }

    fun create(): TranslateApi = TranslateApiImpl(httpClient = defaultHttpClient)
}
