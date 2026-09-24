package net.primal.android.core.webview

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.primal.core.networking.tor.TorEngineType
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.networking.tor.engine.BuiltInTor

/**
 * WebView runs on Chromium's own network stack, entirely separate from OkHttp/Ktor — none of the
 * proxy wiring in [net.primal.core.networking.tor] reaches it on its own. This applies the same Tor
 * SOCKS proxy globally to WebView via the AndroidX proxy-override API (matching the "restart
 * required" behavior of every other Tor-aware client in the app). A no-op on devices where
 * [WebViewFeature.PROXY_OVERRIDE] isn't supported, or when Tor is off.
 *
 * Orbot's port is fixed, so it is applied once. The built-in engine's port is chosen by the engine and
 * changes whenever it restarts, so for that engine the override follows the engine's state and is
 * re-applied on every change. When the engine has no port the override points at a port where nothing
 * listens: a page then fails to load, it does not load directly.
 */
object WebViewProxyConfigurer {

    private const val LOOPBACK = "127.0.0.1"
    private const val UNROUTABLE_PORT = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        when (settings.engine) {
            TorEngineType.ORBOT -> setOverride(port = settings.socksPort)
            TorEngineType.BUILT_IN -> scope.launch {
                BuiltInTor.state.map { it.socksPort }.distinctUntilChanged().collect { port ->
                    setOverride(port = port ?: UNROUTABLE_PORT)
                }
            }
        }
    }

    private fun setOverride(port: Int) {
        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("socks5://$LOOPBACK:$port")
            .build()

        ProxyController.getInstance().setProxyOverride(proxyConfig, Executor { it.run() }, {})
    }
}
