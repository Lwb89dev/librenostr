package net.primal.core.translation

/**
 * Thin Kotlin wrapper around the JNI bridge to bergamot-translator (see
 * `src/main/cpp/jni_bridge.cpp`). One instance of this class owns exactly one
 * loaded (source, target) language pair's model — construction is the
 * expensive part (loading and indexing the model weights, on the order of
 * tens to low hundreds of milliseconds for these "tiny" packs), so callers
 * are expected to hold onto an instance and reuse it across translate() calls
 * for the same pair, not recreate one per note.
 *
 * All I/O (which pack to load, whether its files exist, downloading a
 * missing one) is intentionally kept out of this class — it only knows how
 * to turn an already-installed model's config file into loaded weights and
 * then translate text with them. See LanguagePackRepository for the
 * install-state/download side of the feature.
 *
 * Not thread-safe for concurrent calls to [translate] on the same instance:
 * the underlying native AsyncService is configured with a single worker
 * thread (translations are short, and giving every pack its own thread pool
 * would be wasteful), so concurrent calls would simply queue on the native
 * side rather than crash, but callers should still serialize their own calls
 * per instance for predictable latency.
 */
class BergamotTranslationEngine private constructor(private var nativeHandle: Long) {

    /**
     * Blocks the calling thread until the native translation completes.
     * Callers must invoke this from a background dispatcher, never from the
     * main/UI thread — this is a native call into a C++ inference engine,
     * not a suspend function, so there is no cooperative cancellation point
     * inside it once started.
     */
    fun translate(text: String): String {
        check(nativeHandle != 0L) { "BergamotTranslationEngine has already been closed" }
        return nativeTranslate(nativeHandle, text)
    }

    /**
     * Releases the native model. Must be called exactly once when this
     * engine is evicted from whatever LRU/cache holds it — the native side
     * does not use any reference counting or finalizer-based cleanup, since
     * relying on GC timing for a multi-megabyte native allocation is exactly
     * the kind of thing that leads to unpredictable memory pressure on a
     * phone.
     */
    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroyModel(nativeHandle)
            nativeHandle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("translation_engine")
        }

        /**
         * @param modelConfigPath path to a Marian/Bergamot YAML config file
         * (already written to app-private storage) referencing the model,
         * shared vocabulary, and lexical shortlist files for one language
         * pair. Returns null if the native side failed to load the model
         * (a corrupted or incompatible pack, surfaced as a load failure
         * rather than a crash — see jni_bridge.cpp's catch block).
         */
        fun create(modelConfigPath: String): BergamotTranslationEngine? {
            val handle = nativeCreateModel(modelConfigPath)
            return if (handle == 0L) null else BergamotTranslationEngine(handle)
        }

        @JvmStatic
        private external fun nativeCreateModel(configPath: String): Long

        @JvmStatic
        private external fun nativeTranslate(handle: Long, text: String): String

        @JvmStatic
        private external fun nativeDestroyModel(handle: Long)
    }
}
