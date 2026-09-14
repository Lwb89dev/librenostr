package net.primal.android.core.images

import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import javax.inject.Inject
import javax.inject.Singleton
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.networking.tor.applyTorProxyIfEnabled
import okio.Path.Companion.toOkioPath
import okhttp3.OkHttpClient

@Singleton
class PrimalImageLoaderFactory @Inject constructor() : SingletonImageLoader.Factory {

    companion object {
        private const val MIN_DISK_CACHE_BYTES = 100L * 1024 * 1024
        private const val MAX_DISK_CACHE_BYTES = 500L * 1024 * 1024
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val defaultBuilder = ImageLoader.Builder(context)
        val imageCacheDir = context.cacheDir.resolve("image_cache").toOkioPath()
        // Wikimedia Commons rejects Coil's default Android user agent with HTTP 403.
        // Use a small dedicated client for image requests and identify LibreNostr
        // according to Wikimedia's API policy. The referer also makes thumbnail
        // requests indistinguishable from a normal Commons page load.
        val imageHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                if (host == "upload.wikimedia.org" || host.endsWith(".wikimedia.org")) {
                    chain.proceed(
                        request.newBuilder()
                            .header(
                                "User-Agent",
                                "LibreNostr/1.0",
                            )
                            .header("Referer", "https://commons.wikimedia.org/")
                            .build(),
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .applyTorProxyIfEnabled(TorProxySettingsStore.readBlocking(context))
            .build()

        return defaultBuilder
            .components {
                // Register before Coil's service-loaded network fetcher so all
                // Wikimedia previews pass through the header interceptor above.
                add(WikimediaCoilFactory.create(imageHttpClient))
                // Gifs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // Feed previews can fall back to a decoded video frame when a Nostr event
                // does not provide a usable thumbnail URL.
                add(VideoFrameDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(imageCacheDir)
                    .maxSizePercent(percent = 0.03)
                    // Bounds around the percentage: a storage-constrained device shouldn't be
                    // squeezed below a cache that can hold a session's worth of feed media, and a
                    // device with lots of free space shouldn't have Coil claim an unbounded share.
                    .minimumMaxSizeBytes(size = MIN_DISK_CACHE_BYTES)
                    .maximumMaxSizeBytes(size = MAX_DISK_CACHE_BYTES)
                    .build()
            }
            .memoryCache {
                // Explicit rather than relying on Coil's implicit default, so this stays a
                // deliberate, documented choice for a media-heavy scrolling feed.
                MemoryCache.Builder()
                    .maxSizePercent(context = context, percent = 0.25)
                    .build()
            }
            .build()
    }
}
