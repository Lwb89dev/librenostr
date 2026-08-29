package net.primal.android.core.images;

import coil3.network.NetworkFetcher;
import coil3.network.okhttp.OkHttpNetworkFetcher;
import okhttp3.Call;

/**
 * Java interop shim for Coil 3's OkHttp factory. The Android artifact exposes
 * this factory as a JVM static method without Kotlin metadata, so calling it
 * from Kotlin directly is not possible with all AGP/Kotlin versions.
 */
public final class WikimediaCoilFactory {
    private WikimediaCoilFactory() {
    }

    public static NetworkFetcher.Factory create(Call.Factory callFactory) {
        return OkHttpNetworkFetcher.factory(callFactory);
    }
}
