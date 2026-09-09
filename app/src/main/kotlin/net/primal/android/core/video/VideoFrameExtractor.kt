package net.primal.android.core.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.primal.core.utils.runCatching

/**
 * Pulls a single frame out of a remote video without downloading it first.
 *
 * Coil's [coil3.video.VideoFrameDecoder] only ever sees bytes its own fetcher already pulled in
 * full — for a feed video that can be tens to hundreds of MB just to render one preview frame,
 * which is slow enough in practice that the preview was showing the gray error surface far more
 * often than an actual frame. [MediaMetadataRetriever.setDataSource] with a plain URL hands the
 * request to the platform's own extractor, which — like ExoPlayer's own data sources — seeks with
 * HTTP range requests instead of reading the whole stream, so this returns in roughly the time of
 * one small ranged fetch regardless of the file's total size.
 *
 * This intentionally does NOT go through [MediaOkHttpClientProvider]: `MediaMetadataRetriever`
 * only accepts a URL and optional headers, not a caller-supplied `OkHttpClient`/`DataSource`, so
 * there is no way to route it through the Tor SOCKS proxy the rest of media playback uses. Callers
 * must check Tor is disabled before calling this — see call site in `NoteAttachmentVideoPreview`.
 */
internal object VideoFrameExtractor {

    private const val EXTRACTION_TIMEOUT_MS = 8_000L
    private const val MICROS_PER_MS = 1_000L

    suspend fun extractFrame(url: String, atTimeMs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(url, emptyMap())
                        retriever.getFrameAtTime(
                            atTimeMs * MICROS_PER_MS,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
        }
}
