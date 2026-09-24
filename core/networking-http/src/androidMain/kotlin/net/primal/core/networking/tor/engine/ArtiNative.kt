package net.primal.core.networking.tor.engine

import io.github.aakira.napier.Napier

/**
 * The JNI declarations for `libnostr_arti.so`, built from `tools/arti-build`.
 *
 * The class and package name are part of the native symbol names
 * (`Java_net_primal_core_networking_tor_engine_ArtiNative_*`), so renaming or moving this object
 * silently breaks every call at runtime. The R8 rule in `app/proguard-rules.pro` keeps it and its
 * native methods from being renamed or stripped in release builds.
 *
 * Loading the library is the first thing that can fail (a build without the arm64 library, an
 * emulator with another ABI), so it happens in `init` and is only ever triggered through
 * [ArtiLibrary.loadOrNull], which turns the failure into "built-in Tor is unavailable".
 */
internal object ArtiNative {

    init {
        System.loadLibrary("nostr_arti")
    }

    @JvmStatic external fun nativeVersion(): String

    @JvmStatic external fun nativeInitialize(dataDir: String): Int

    @JvmStatic external fun nativeStartSocks(port: Int): Int

    @JvmStatic external fun nativeStopSocks(): Int

    @JvmStatic external fun nativeIsBootstrapped(): Int

    @JvmStatic external fun nativeBootstrapProgress(): Int

    @JvmStatic external fun nativeSetDormant(soft: Boolean)

    @JvmStatic external fun nativeStat(index: Int): Long

    @JvmStatic external fun nativePollLog(): String?

    @JvmStatic external fun nativeDestroy(): Int
}

/** [ArtiBridge] over JNI. Constructing it loads the native library. */
internal class JniArtiBridge : ArtiBridge {
    override fun version(): String = ArtiNative.nativeVersion()

    override fun initialize(dataDir: String): Int = ArtiNative.nativeInitialize(dataDir)

    override fun startSocks(port: Int): Int = ArtiNative.nativeStartSocks(port)

    override fun stopSocks(): Int = ArtiNative.nativeStopSocks()

    override fun isBootstrapped(): Int = ArtiNative.nativeIsBootstrapped()

    override fun bootstrapProgress(): Int = ArtiNative.nativeBootstrapProgress()

    override fun setDormant(soft: Boolean) = ArtiNative.nativeSetDormant(soft)

    override fun stat(index: Int): Long = ArtiNative.nativeStat(index)

    override fun pollLog(): String? = ArtiNative.nativePollLog()

    override fun destroy(): Int = ArtiNative.nativeDestroy()
}

object ArtiLibrary {

    /**
     * The JNI bridge, or null when this build or device cannot run built-in Tor: the library is not
     * packaged (only arm64-v8a is built) or does not load.
     *
     * Catches [LinkageError] rather than only `UnsatisfiedLinkError`: a library that is present but
     * fails to link (a truncated file, the wrong architecture) surfaces as a different subclass, and
     * none of them may take the app down — the settings screen shows "not available" instead.
     */
    fun loadOrNull(): ArtiBridge? =
        try {
            JniArtiBridge().also { Napier.i { "Built-in Tor library loaded: ${it.version()}" } }
        } catch (error: LinkageError) {
            Napier.w(error) { "Built-in Tor library is not available in this build" }
            null
        }
}
