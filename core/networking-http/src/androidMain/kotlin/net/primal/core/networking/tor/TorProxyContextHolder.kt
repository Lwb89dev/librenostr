package net.primal.core.networking.tor

import android.content.Context

/**
 * The Ktor OkHttp engine factory ([net.primal.core.networking.http.createHttpClientEngine])
 * is a plain top-level function with no Hilt scope reaching it, so it cannot receive an
 * injected Context the way every other Tor-aware call site in the app can. This is the one
 * static holder that exists purely to bridge that gap. Every other consumer of
 * [TorProxySettingsStore] should take `@ApplicationContext Context` directly instead of
 * depending on this holder's seeding order.
 */
object TorProxyContextHolder {

    @Volatile
    private var appContext: Context? = null

    fun seed(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun require(): Context =
        appContext ?: error(
            "TorProxyContextHolder not seeded. Call seed(context) as the first statement in " +
                "Application.onCreate(), before super.onCreate().",
        )
}
