package net.primal.android.core.images

import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import javax.inject.Inject
import javax.inject.Singleton
import okio.Path.Companion.toOkioPath
import okhttp3.OkHttpClient

@Singleton
class PrimalImageLoaderFactory @Inject constructor() : SingletonImageLoader.Factory {

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
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(imageCacheDir)
                    .maxSizePercent(percent = 0.02)
                    .build()
            }
            .build()
    }
}
