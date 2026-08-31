package net.primal.core.networking.media

import io.github.aakira.napier.Napier
import okio.Buffer
import okio.BufferedSource

/**
 * Strips metadata from still images before they leave the device.
 *
 * A photo straight from a camera carries EXIF: GPS coordinates to a few metres, the exact
 * capture timestamp, the device make/model and serial, and often the owner's name. Uploading
 * it to a Blossom server publishes all of that alongside the picture, where it outlives any
 * later deletion of the note.
 *
 * The rewrite is byte-level: containers are re-emitted without their metadata segments and
 * the pixel data is copied verbatim. Nothing is re-encoded, so there is no quality loss and
 * no dependency on a platform image decoder. Formats that are not recognised, and anything
 * larger than [MAX_SCRUBBABLE_BYTES] (video, mainly), stream through untouched.
 */
object ImageMetadataScrubber {

    /** Images are read fully into memory to be rewritten; videos must never be. */
    private const val MAX_SCRUBBABLE_BYTES = 32L * 1024 * 1024

    private const val SNIFF_BYTES = 12L

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val RIFF_MAGIC = "RIFF".encodeToByteArray()
    private val WEBP_MAGIC = "WEBP".encodeToByteArray()

    /**
     * Returns a source whose bytes carry no image metadata. The original [source] is returned
     * unchanged when the payload is not a recognised still image, is too large to buffer, or
     * cannot be parsed — scrubbing must never be the reason an upload fails.
     */
    fun scrubbing(source: BufferedSource): BufferedSource {
        val header = runCatching { source.peek().readByteArray(SNIFF_BYTES) }.getOrNull()
            ?: return source

        val format = detectFormat(header) ?: return source

        val bytes = runCatching {
            val peek = source.peek()
            // Bounded read: a video that slipped past the sniff must not be pulled into memory.
            val buffered = peek.readByteArray(minOf(peek.buffer.size, MAX_SCRUBBABLE_BYTES))
            if (buffered.size.toLong() >= MAX_SCRUBBABLE_BYTES) null else source.readByteArray()
        }.getOrNull() ?: return source

        if (bytes.size.toLong() > MAX_SCRUBBABLE_BYTES) return Buffer().apply { write(bytes) }

        val scrubbed = runCatching {
            when (format) {
                Format.JPEG -> scrubJpeg(bytes)
                Format.PNG -> scrubPng(bytes)
                Format.WEBP -> scrubWebp(bytes)
            }
        }.getOrNull() ?: bytes

        if (scrubbed.size != bytes.size) {
            Napier.i { "Stripped ${bytes.size - scrubbed.size} metadata bytes from a $format upload." }
        }
        return Buffer().apply { write(scrubbed) }
    }

    private enum class Format { JPEG, PNG, WEBP }

    private fun detectFormat(header: ByteArray): Format? =
        when {
            header.startsWith(JPEG_MAGIC) -> Format.JPEG
            header.startsWith(PNG_MAGIC) -> Format.PNG
            header.size >= 12 && header.startsWith(RIFF_MAGIC) &&
                header.copyOfRange(8, 12).contentEquals(WEBP_MAGIC) -> Format.WEBP
            else -> null
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    // ------------------------------------------------------------------ JPEG

    /**
     * JPEG metadata lives in APPn application segments and COM comments between SOI and SOS.
     * APP0 (JFIF density) and APP2 (ICC colour profile) are kept because dropping them changes
     * how the image renders; neither carries personal data. Everything else in the APP1..APP15
     * range goes, which covers EXIF and XMP (APP1) and Photoshop/IPTC author records (APP13).
     * From SOS onward the payload is entropy-coded scan data and is copied verbatim.
     */
    private fun scrubJpeg(bytes: ByteArray): ByteArray {
        val out = Buffer()
        out.write(bytes, 0, 2) // SOI
        var i = 2

        while (i + 3 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return bytes // not a marker boundary; leave it alone
            val marker = bytes[i + 1].toInt() and 0xFF

            if (marker == SOS_MARKER) {
                out.write(bytes, i, bytes.size - i)
                return out.readByteArray()
            }

            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > bytes.size) return bytes

            if (!isStrippableJpegMarker(marker)) {
                out.write(bytes, i, 2 + length)
            }
            i += 2 + length
        }
        return out.readByteArray()
    }

    private fun isStrippableJpegMarker(marker: Int): Boolean =
        marker == COM_MARKER || (marker in APP1_MARKER..APP15_MARKER && marker != APP2_MARKER)

    // ------------------------------------------------------------------- PNG

    /**
     * PNG metadata sits in ancillary chunks. Textual chunks and eXIf carry camera, location and
     * authorship data; tIME carries the modification timestamp. Colour chunks (gAMA, cHRM, sRGB,
     * iCCP) are kept so the image still renders as intended.
     */
    private fun scrubPng(bytes: ByteArray): ByteArray {
        val out = Buffer()
        out.write(bytes, 0, PNG_MAGIC.size)
        var i = PNG_MAGIC.size

        while (i + 8 <= bytes.size) {
            val length = readInt32(bytes, i)
            if (length < 0) return bytes
            val type = bytes.decodeAscii(i + 4, 4)
            val total = 12 + length // length + type + data + crc
            if (i + total > bytes.size) return bytes

            if (type !in STRIPPABLE_PNG_CHUNKS) {
                out.write(bytes, i, total)
            }
            i += total
            if (type == "IEND") break
        }
        return out.readByteArray()
    }

    // ------------------------------------------------------------------ WEBP

    /**
     * WebP is a RIFF container; EXIF and XMP ride in their own chunks. Dropping them means the
     * RIFF size field in the header has to be rewritten to match the shortened payload.
     */
    private fun scrubWebp(bytes: ByteArray): ByteArray {
        val payload = Buffer()
        var i = 12 // "RIFF" + size + "WEBP"

        while (i + 8 <= bytes.size) {
            val fourCc = bytes.decodeAscii(i, 4)
            val size = readInt32LittleEndian(bytes, i + 4)
            if (size < 0) return bytes
            val padded = size + (size and 1) // chunks are padded to an even length
            val total = 8 + padded
            if (i + total > bytes.size) return bytes

            if (fourCc !in STRIPPABLE_WEBP_CHUNKS) {
                payload.write(bytes, i, total)
            }
            i += total
        }

        val body = payload.readByteArray()
        val out = Buffer()
        out.write(RIFF_MAGIC)
        out.write(int32LittleEndian(body.size + WEBP_MAGIC.size))
        out.write(WEBP_MAGIC)
        out.write(body)
        return out.readByteArray()
    }

    // ---------------------------------------------------------------- helpers

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        buildString { for (k in 0 until length) append((this@decodeAscii[offset + k].toInt() and 0xFF).toChar()) }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        val value = ((bytes[offset].toInt() and 0xFF).toLong() shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (bytes[offset + 3].toInt() and 0xFF).toLong()
        return if (value > Int.MAX_VALUE) -1 else value.toInt()
    }

    private fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xFF).toLong() or
            ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF).toLong() shl 24)
        return if (value > Int.MAX_VALUE) -1 else value.toInt()
    }

    private fun int32LittleEndian(value: Int) =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private const val SOS_MARKER = 0xDA
    private const val COM_MARKER = 0xFE
    private const val APP1_MARKER = 0xE1
    private const val APP2_MARKER = 0xE2
    private const val APP15_MARKER = 0xEF

    private val STRIPPABLE_PNG_CHUNKS = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")
    private val STRIPPABLE_WEBP_CHUNKS = setOf("EXIF", "XMP ")
}
