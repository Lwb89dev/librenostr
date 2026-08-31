package net.primal.core.networking.blossom

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import net.primal.core.networking.media.ImageMetadataScrubber
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.NostrEventSignatureHandler
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source

class AndroidPrimalBlossomUploadService(
    blossomResolver: BlossomServerListProvider,
    signatureHandler: NostrEventSignatureHandler,
    private val contentResolver: ContentResolver,
) {

    private val uploadService by lazy {
        PrimalUploadService(
            dispatchers = createDispatcherProvider(),
            blossomResolver = blossomResolver,
            signatureHandler = signatureHandler,
        )
    }

    suspend fun upload(
        uri: Uri,
        userId: String,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)? = null,
    ): UploadResult {
        return uploadService.upload(
            userId = userId,
            openBufferedSource = uri.scrubbedSourceFactory(),
            onProgress = onProgress,
        )
    }

    suspend fun upload(
        uri: Uri,
        userId: String,
        onSignRequested: (NostrUnsignedEvent) -> NostrEvent,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)? = null,
    ): UploadResult {
        return uploadService.upload(
            userId = userId,
            openBufferedSource = uri.scrubbedSourceFactory(),
            onProgress = onProgress,
            onSignRequested = onSignRequested,
        )
    }

    /**
     * Builds the source factory the upload service calls repeatedly — once to derive the
     * file metadata and again for the upload to each Blossom server.
     *
     * Metadata is derived from whatever this factory yields, so scrubbing here is what keeps
     * the advertised SHA-256 equal to the bytes actually stored. The scrub runs once and its
     * result is reused, rather than re-running for every mirror.
     *
     * The first call decides: if the payload is a still image it is rewritten without its
     * metadata and cached; anything else (video above all) keeps streaming straight from the
     * ContentResolver so it is never pulled into memory.
     */
    private fun Uri.scrubbedSourceFactory(): () -> BufferedSource {
        var scrubbed: ByteArray? = null
        var scrubAttempted = false

        return {
            val cached = scrubbed
            when {
                cached != null -> Buffer().apply { write(cached) }

                scrubAttempted -> openRawSource()

                else -> {
                    scrubAttempted = true
                    val source = ImageMetadataScrubber.scrubbing(openRawSource())
                    val buffer = source as? Buffer
                    if (buffer != null) {
                        // Scrubbed payloads are already fully materialised; keep them for the mirrors.
                        scrubbed = buffer.snapshot().toByteArray()
                        buffer
                    } else {
                        source
                    }
                }
            }
        }
    }

    private fun Uri.openRawSource(): BufferedSource {
        return contentResolver.openInputStream(this)?.source()?.buffer()
            ?: throw IOException("Unable to open input stream.")
    }
}
