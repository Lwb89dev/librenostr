package net.primal.core.networking.http

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import net.primal.core.networking.tor.TorProxyContextHolder
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.networking.tor.applyTorProxyIfEnabled

// Read once, at first use of HttpClientFactory's `engine` val (a Kotlin `object` class-
// initializes lazily) — the Tor toggle in Settings is documented as requiring an app restart
// to take effect, so there is deliberately no reactive re-read here.
actual fun createHttpClientEngine(): HttpClientEngineFactory<*> {
    val torSettings = TorProxySettingsStore.readBlocking(TorProxyContextHolder.require())
    return object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
            OkHttp.create {
                config { applyTorProxyIfEnabled(torSettings) }
                block()
            }
    }
}
