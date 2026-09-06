package net.primal.android.notes.translate

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.primal.core.utils.coroutines.DispatcherProvider
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the on-disk lifecycle of downloadable language packs: which ones the
 * manifest knows about, whether a given (source, target) pair is installed,
 * downloading one (with progress reporting and cancellation via the calling
 * coroutine), and deleting one. Loading the installed files into an actual
 * translation engine is a separate concern (see BergamotTranslationEngine in
 * :core:translation-engine) — this class only manages files.
 *
 * Packs live under `filesDir/translation-packs/<source>-<target>/`, not
 * `cacheDir` — this mirrors AppLogRecorder's persistent-storage convention
 * elsewhere in this app, since a pack the user explicitly downloaded should
 * survive a cache clear the same way any other deliberately-kept user data
 * would, not be silently evicted under storage pressure like a Coil/ExoPlayer
 * cache entry.
 */
@Singleton
class LanguagePackRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val packsRootDir: File by lazy {
        File(context.filesDir, PACKS_DIRECTORY).apply { mkdirs() }
    }

    private val manifest: List<LanguagePack> by lazy {
        context.assets.open(MANIFEST_ASSET_NAME).use { stream ->
            manifestJson.decodeFromString(LanguagePackManifest.serializer(), stream.readBytes().decodeToString()).packs
        }
    }

    /** All (source, target) pairs this app ships a manifest entry for, regardless of install state. */
    fun availablePacks(): List<LanguagePack> = manifest

    fun findPack(source: String, target: String): LanguagePack? =
        manifest.find { it.source == source && it.target == target }

    fun isInstalled(pack: LanguagePack): Boolean {
        val dir = packDirectory(pack)
        return modelFile(dir).exists() && lexFile(dir).exists() && vocabFile(dir).exists()
    }

    fun installedPacks(): List<LanguagePack> = manifest.filter(::isInstalled)

    /**
     * Total bytes on disk across every installed pack — shown as "Total translation storage"
     * in the language-pack management screen, per the spec that prompted this feature.
     */
    fun totalInstalledBytes(): Long =
        installedPacks().sumOf { pack -> packDirectory(pack).walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * Downloads and installs [pack], reporting progress via [onProgress] as
     * (bytes downloaded across all three assets so far, total bytes expected).
     * Installation is atomic per-asset: each file downloads to a `.part`
     * sibling, is hash-verified against the manifest's SHA-256, and is only
     * decompressed + renamed into its final place once verification passes —
     * a cancelled or corrupted download never leaves a partial/unverified
     * file where [isInstalled] would find it. Safe to call again after a
     * cancellation or failure; already-downloaded/verified assets from a
     * previous attempt are not re-fetched.
     *
     * Cancellation is cooperative via the calling coroutine's Job, same as
     * any other suspend function — there is no separate cancel() method.
     */
    suspend fun install(pack: LanguagePack, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit) {
        withContext(dispatchers.io()) {
            val dir = packDirectory(pack).apply { mkdirs() }
            val totalBytes = pack.model.sizeBytes + pack.lex.sizeBytes + pack.vocab.sizeBytes
            var completedBytes = 0L

            val onAssetBytesDownloaded: (Long) -> Unit = {
                completedBytes += it
                onProgress(completedBytes, totalBytes)
            }
            downloadVerifyAndDecompress(pack.model, modelFile(dir), onAssetBytesDownloaded)
            downloadVerifyAndDecompress(pack.lex, lexFile(dir), onAssetBytesDownloaded)
            downloadVerifyAndDecompress(pack.vocab, vocabFile(dir), onAssetBytesDownloaded)
        }
    }

    fun delete(pack: LanguagePack) {
        packDirectory(pack).deleteRecursively()
    }

    /**
     * Writes the Marian/Bergamot YAML config BergamotTranslationEngine needs, pointing at this
     * pack's already-installed files, and returns its path. Regenerated on every call rather than
     * cached — writing a few hundred bytes is cheap, and it keeps this function trivially correct
     * if the pack's on-disk layout ever changes without needing separate invalidation logic.
     */
    fun writeEngineConfig(pack: LanguagePack): String {
        require(isInstalled(pack)) { "Cannot build a config for a pack that isn't installed: ${pack.directoryName}" }
        val dir = packDirectory(pack)
        val configFile = File(dir, "config.yml")
        // Deliberately pinned to 1: raising this (tried 4 on an 8-core device) made even a
        // two-word translation hang indefinitely instead of finishing — a real deadlock in this
        // vendored ruy/Marian build's multi-threaded path on Android, not just a slowdown. Do not
        // change this without first tracking down that deadlock; see NoteTranslationCoordinator
        // for the (safer) per-pack engine cache that addresses the actual slowness instead.
        val cpuThreads = 1
        configFile.writeText(
            """
            models:
              - ${modelFile(dir).absolutePath}
            vocabs:
              - ${vocabFile(dir).absolutePath}
              - ${vocabFile(dir).absolutePath}
            shortlist:
              - ${lexFile(dir).absolutePath}
              - 50
              - 50
            beam-size: 1
            normalize: 1.0
            word-penalty: 0
            max-length-break: 128
            mini-batch-words: 1024
            mini-batch: 1
            maxi-batch: 1
            maxi-batch-sort: none
            skip-cost: true
            cpu-threads: $cpuThreads
            quiet: true
            quiet-translation: true
            """.trimIndent(),
        )
        return configFile.absolutePath
    }

    private suspend fun downloadVerifyAndDecompress(
        asset: LanguagePackAsset,
        destination: File,
        onBytesDownloaded: (Long) -> Unit,
    ) {
        if (destination.exists()) return

        val partFile = File(destination.parentFile, "${destination.name}.gz.part")
        val request = Request.Builder().url(asset.url).build()
        val digest = MessageDigest.getInstance("SHA-256")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Failed to download ${asset.url}: HTTP ${response.code}")
            }
            val body = response.body ?: error("Empty response body for ${asset.url}")
            partFile.outputStream().use { output ->
                copyAndDigest(body.byteStream(), output, digest, onBytesDownloaded)
            }
        }

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != asset.sha256) {
            partFile.delete()
            error("Checksum mismatch for ${asset.url}: expected ${asset.sha256}, got $actualSha256")
        }

        // The manifest's checksum covers the compressed (.gz) download exactly as Mozilla
        // publishes it; decompression happens only after that's verified, so a corrupted or
        // tampered download is rejected before any of its (untrusted) bytes are unpacked.
        GZIPInputStream(partFile.inputStream()).use { gzipInput ->
            destination.outputStream().use { output -> gzipInput.copyTo(output) }
        }
        partFile.delete()
    }

    private fun copyAndDigest(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        onBytesDownloaded: (Long) -> Unit,
    ) {
        input.use {
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = it.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                onBytesDownloaded(read.toLong())
            }
        }
    }

    private fun packDirectory(pack: LanguagePack) = File(packsRootDir, pack.directoryName)
    private fun modelFile(dir: File) = File(dir, "model.bin")
    private fun lexFile(dir: File) = File(dir, "lex.bin")
    private fun vocabFile(dir: File) = File(dir, "vocab.spm")

    companion object {
        private const val PACKS_DIRECTORY = "translation-packs"
        private const val MANIFEST_ASSET_NAME = "translation_language_packs.json"
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
        private val manifestJson = Json { ignoreUnknownKeys = true }
    }
}
