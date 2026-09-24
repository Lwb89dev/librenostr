package net.primal.android.core.video

import net.primal.core.networking.tor.applyNetworkRoute
import okhttp3.OkHttpClient

/**
 * ExoPlayer's default data sources (DefaultHttpDataSource / DefaultDataSource) use their own
 * HttpURLConnection-based stack, entirely separate from the OkHttp/Ktor path the rest of the app
 * routes through HttpClientFactory — meaning video and live-stream playback would silently bypass
 * the network mode even with Tor selected. This is the one shared OkHttpClient for media
 * playback that follows it, wired into an OkHttpDataSource.Factory at both ExoPlayer construction
 * sites.
 */
object MediaOkHttpClientProvider {

    @Volatile
    private var client: OkHttpClient? = null

    fun get(): OkHttpClient =
        client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .applyNetworkRoute()
                .build()
                .also { client = it }
        }
}
