package net.primal.android.core.webview

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.primal.core.networking.tor.NetworkMode
import net.primal.core.networking.tor.NetworkRoute
import net.primal.core.networking.tor.RouteConfig
import net.primal.core.networking.tor.TorEngineType
import net.primal.core.networking.tor.engine.BuiltInTor

/**
 * WebView runs on Chromium's own network stack, entirely separate from OkHttp/Ktor: none of the
 * routing in [net.primal.core.networking.tor] reaches it on its own. This applies the current network
 * mode to it through the AndroidX proxy-override API, and keeps it applied as the mode changes.
 * A no-op on devices where [WebViewFeature.PROXY_OVERRIDE] isn't supported.
 *
 * - **Direct**: no override.
 * - **Tor for everything**: every request goes to the Tor SOCKS proxy. With SOCKS5 Chromium hands page
 *   host names to the proxy unresolved, so no name is looked up locally.
 * - **Only `.onion`**: the same proxy, but only for `*.onion` hosts (a "reverse bypass" list: the
 *   listed hosts use the proxy, everything else goes direct). Where the WebView is too old for that,
 *   no override is set, which is what this mode means for everything but onion pages anyway.
 *
 * The built-in engine's port is chosen by the engine and changes whenever it restarts, so the override
 * follows the engine's state. When it has no port the override points at one where nothing listens:
 * a page then fails to load, it does not load directly.
 */
object WebViewProxyConfigurer {

    private const val LOOPBACK = "127.0.0.1"
    private const val UNROUTABLE_PORT = 1
    private const val ONION_PATTERN = "*.onion"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var configured = false

    /** Whether an override is currently set, so leaving Tor mode clears it and a direct start never touches it. */
    private var overrideActive = false

    /**
     * Whether a page may be loaded at all. In Tor mode a WebView that cannot be proxied must not load
     * anything: without the override it would fetch the page directly, which is the one thing that
     * mode exists to prevent. Every other mode is unaffected.
     */
    fun mayLoadPages(): Boolean =
        NetworkRoute.controller.config.mode != NetworkMode.TOR ||
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun ensureConfigured() {
        if (configured) return
        synchronized(this) {
            if (configured) return
            configured = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) follow()
        }
    }

    private fun follow() {
        val config = NetworkRoute.controller.state.map { it.config }
        val builtInPort = BuiltInTor.state.map { it.socksPort }
        scope.launch {
            combine(config, builtInPort) { current, port -> current to port }
                .distinctUntilChanged()
                .collect { (current, port) -> apply(current, port) }
        }
    }

    private fun apply(config: RouteConfig, builtInPort: Int?) {
        if (config.mode == NetworkMode.DIRECT) {
            clearOverride()
            return
        }
        val port = when (config.engine) {
            TorEngineType.ORBOT -> config.orbotPort
            TorEngineType.BUILT_IN -> builtInPort ?: UNROUTABLE_PORT
        }
        val proxy = ProxyConfig.Builder().addProxyRule("socks5://$LOOPBACK:$port")
        if (config.mode == NetworkMode.ONION_ONLY) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                clearOverride()
                return
            }
            proxy.addBypassRule(ONION_PATTERN).setReverseBypassEnabled(true)
        }
        overrideActive = true
        ProxyController.getInstance().setProxyOverride(proxy.build(), Executor { it.run() }, {})
    }

    private fun clearOverride() {
        if (!overrideActive) return
        overrideActive = false
        ProxyController.getInstance().clearProxyOverride(Executor { it.run() }, {})
    }
}
