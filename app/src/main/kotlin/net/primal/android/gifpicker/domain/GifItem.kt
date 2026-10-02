package net.primal.android.gifpicker.domain

import kotlinx.serialization.Serializable
import net.primal.data.remote.api.gifs.model.GifResult

/**
 * A GIF as the picker shows it and as the composer posts it.
 *
 * Serializable because it travels between screens: the full-screen picker hands it back through
 * the navigation back stack, and a GIF reply from a thread carries it inside the composer's
 * arguments. It keeps the metadata the provider gave us so the published note can describe the
 * file in a NIP-92 `imeta` tag (type, dimensions, size, alt text) — other clients then reserve the
 * right space before the GIF has loaded, instead of jumping the feed when it does.
 */
@Serializable
data class GifItem(
    val id: String,
    val url: String,
    val previewUrl: String,
    val mimeType: String = "image/gif",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long? = null,
    val contentDescription: String = "",
)

fun GifResult.asGifItem(): GifItem =
    GifItem(
        id = id,
        url = url,
        previewUrl = previewUrl,
        mimeType = mimeType,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        contentDescription = title,
    )
