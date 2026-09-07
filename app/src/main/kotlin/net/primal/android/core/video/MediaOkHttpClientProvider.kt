package net.primal.android.core.video

import android.content.Context
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.networking.tor.applyTorProxyIfEnabled
import okhttp3.OkHttpClient

/**
 * ExoPlayer's default data sources (DefaultHttpDataSource / DefaultDataSource) use their own
 * HttpURLConnection-based stack, entirely separate from the OkHttp/Ktor path the rest of the app
 * routes through HttpClientFactory — meaning video and live-stream playback would silently bypass
 * the Tor proxy even with it enabled. This is the one shared, Tor-aware OkHttpClient for media
 * playback, wired into an OkHttpDataSource.Factory at both ExoPlayer construction sites.
 */
object MediaOkHttpClientProvider {

    @Volatile
    private var client: OkHttpClient? = null

    fun get(context: Context): OkHttpClient =
        client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .applyTorProxyIfEnabled(TorProxySettingsStore.readBlocking(context))
                .build()
                .also { client = it }
        }
}
