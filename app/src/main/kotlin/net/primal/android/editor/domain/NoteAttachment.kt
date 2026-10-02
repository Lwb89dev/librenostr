package net.primal.android.editor.domain

import android.net.Uri
import java.util.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

data class NoteAttachment(
    val id: UUID = UUID.randomUUID(),
    val localUri: Uri,
    val remoteUrl: String? = null,
    val mimeType: String? = null,
    val originalHash: String? = null,
    val uploadedHash: String? = null,
    val originalUploadedInBytes: Int? = null,
    val originalSizeInBytes: Int? = null,
    val uploadedSizeInBytes: Int? = null,
    val dimensionInPixels: String? = null,
    val durationInSeconds: Double? = null,
    val bitrateInBitsPerSec: Long? = null,
    /** NIP-92 `alt`: a description of the media for readers who cannot see it. */
    val altText: String? = null,
    val uploadError: Throwable? = null,
) {
    val isImageAttachment: Boolean get() = mimeType?.startsWith("image") == true
    val isVideoAttachment: Boolean get() = mimeType?.startsWith("video") == true
    val isAudioAttachment: Boolean get() = mimeType?.startsWith("audio") == true
    val isMediaAttachment: Boolean get() = isImageAttachment || isVideoAttachment || isAudioAttachment
}

fun NoteAttachment.asIMetaTag(): JsonArray {
    require(this.remoteUrl != null)
    return buildJsonArray {
        add("imeta")
        add("url ${this@asIMetaTag.remoteUrl}")
        this@asIMetaTag.mimeType?.let { add("m $it") }
        this@asIMetaTag.uploadedHash?.let { add("x $it") }
        this@asIMetaTag.originalHash?.let { add("ox $it") }
        this@asIMetaTag.uploadedSizeInBytes?.let { add("size $it") }
        this@asIMetaTag.dimensionInPixels?.let { add("dim $it") }
        this@asIMetaTag.durationInSeconds?.let { add("duration $it") }
        this@asIMetaTag.bitrateInBitsPerSec?.let { add("bitrate $it") }
        // Collapsed to one line: an imeta entry is a single "key value" string, and a newline in
        // free text from a provider would make it look like a malformed tag to some parsers.
        this@asIMetaTag.altText?.let { add("alt ${it.replace(Regex("\\s+"), " ").trim()}") }
    }
}
