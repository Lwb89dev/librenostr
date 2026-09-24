package net.primal.core.networking.http

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import net.primal.core.networking.tor.applyNetworkRoute

// Every Ktor client in the app is built on this engine, so this is the chokepoint that puts all of
// their traffic (API calls, NIP-05, Blossom, LNURL, and the relay WebSockets) under the network mode.
// The mode is consulted on every connection, not read once here, so switching it needs no restart.
actual fun createHttpClientEngine(): HttpClientEngineFactory<*> =
    object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
            OkHttp.create {
                config { applyNetworkRoute() }
                block()
            }
    }
