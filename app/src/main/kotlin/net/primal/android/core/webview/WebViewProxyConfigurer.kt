package net.primal.android.core.webview

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import net.primal.core.networking.tor.TorProxySettingsStore

/**
 * WebView runs on Chromium's own network stack, entirely separate from OkHttp/Ktor — none of the
 * proxy wiring in [net.primal.core.networking.tor] reaches it on its own. This applies the same
 * Orbot SOCKS proxy globally to WebView via the AndroidX proxy-override API, once per process
 * (matching the "restart required" behavior of every other Tor-aware client in the app). A no-op
 * on devices where [WebViewFeature.PROXY_OVERRIDE] isn't supported, or when Tor is off.
 */
object WebViewProxyConfigurer {

    @Volatile
    private var configured = false

    fun ensureConfigured(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            configured = true
            applyOverride(context)
        }
    }

    private fun applyOverride(context: Context) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return

        val settings = TorProxySettingsStore.readBlocking(context)
        if (!settings.enabled) return

        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("socks5://127.0.0.1:${settings.socksPort}")
            .build()

        ProxyController.getInstance().setProxyOverride(proxyConfig, Executor { it.run() }, {})
    }
}
