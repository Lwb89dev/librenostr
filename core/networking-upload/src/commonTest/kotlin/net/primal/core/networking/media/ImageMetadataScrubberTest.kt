package net.primal.core.networking.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Buffer

class ImageMetadataScrubberTest {

    private fun scrub(bytes: ByteArray): ByteArray =
        ImageMetadataScrubber.scrubbing(Buffer().apply { write(bytes) }).readByteArray()

    // ------------------------------------------------------------------ JPEG

    @Test
    fun jpeg_dropsTheExifSegment_andKeepsTheScanData() {
        val exifPayload = "Exif  GPSLatitude 45.4642 GPSLongitude 9.1900".encodeToByteArray()
        val scan = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val jpeg = buildJpeg(segments = listOf(APP1 to exifPayload), scanData = scan)

        val result = scrub(jpeg)

        assertFalse(result.containsAscii("GPSLatitude"), "GPS coordinates survived the scrub")
        assertFalse(result.containsAscii("Exif"), "the Exif marker survived the scrub")
        assertTrue(result.size < jpeg.size)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), result.copyOfRange(0, 2))
        assertTrue(result.endsWithBytes(scan), "scan data was altered")
    }

    @Test
    fun jpeg_keepsJfifAndIccBecauseTheyAffectRendering() {
        val jfif = "JFIF ".encodeToByteArray()
        val icc = "ICC_PROFILE ".encodeToByteArray()
        val jpeg = buildJpeg(
            segments = listOf(APP0 to jfif, APP1 to "Exif  secret".encodeToByteArray(), APP2 to icc),
            scanData = byteArrayOf(0x01),
        )

        val result = scrub(jpeg)

        assertTrue(result.containsAscii("JFIF"), "JFIF density segment was dropped")
        assertTrue(result.containsAscii("ICC_PROFILE"), "ICC colour profile was dropped")
        assertFalse(result.containsAscii("secret"))
    }

    @Test
    fun jpeg_dropsPhotoshopIptcAndComments() {
        val jpeg = buildJpeg(
            segments = listOf(
                APP13 to "Photoshop 3.0 author Mario Rossi".encodeToByteArray(),
                COM to "shot on my phone".encodeToByteArray(),
            ),
            scanData = byteArrayOf(0x07),
        )

        val result = scrub(jpeg)

        assertFalse(result.containsAscii("Mario Rossi"), "IPTC author record survived")
        assertFalse(result.containsAscii("shot on my phone"), "JPEG comment survived")
    }

    @Test
    fun jpeg_withoutMetadata_isReturnedUnchanged() {
        val jpeg = buildJpeg(segments = emptyList(), scanData = byteArrayOf(0x09, 0x08))
        assertContentEquals(jpeg, scrub(jpeg))
    }

    // ------------------------------------------------------------------- PNG

    @Test
    fun png_dropsExifAndTextChunks_butKeepsImageData() {
        val png = buildPng(
            chunks = listOf(
                "IHDR" to ByteArray(13),
                "eXIf" to "GPSLatitude 45.4642".encodeToByteArray(),
                "tEXt" to "Author Mario Rossi".encodeToByteArray(),
                "sRGB" to byteArrayOf(0),
                "IDAT" to byteArrayOf(0x42, 0x43),
                "IEND" to ByteArray(0),
            ),
        )

        val result = scrub(png)

        assertFalse(result.containsAscii("GPSLatitude"))
        assertFalse(result.containsAscii("Mario Rossi"))
        assertTrue(result.containsAscii("IHDR"), "header chunk was dropped")
        assertTrue(result.containsAscii("sRGB"), "colour chunk was dropped")
        assertTrue(result.containsAscii("IDAT"), "image data was dropped")
        assertTrue(result.containsAscii("IEND"))
    }

    // ------------------------------------------------------------------ WEBP

    @Test
    fun webp_dropsExifChunk_andRewritesTheRiffSize() {
        val webp = buildWebp(
            chunks = listOf(
                "VP8 " to byteArrayOf(1, 2, 3, 4),
                "EXIF" to "GPSLatitude 45.4642".encodeToByteArray(),
            ),
        )

        val result = scrub(webp)

        assertFalse(result.containsAscii("GPSLatitude"))
        assertTrue(result.containsAscii("VP8 "), "image data chunk was dropped")
        val declared = (result[4].toInt() and 0xFF) or ((result[5].toInt() and 0xFF) shl 8) or
            ((result[6].toInt() and 0xFF) shl 16) or ((result[7].toInt() and 0xFF) shl 24)
        assertEquals(result.size - 8, declared, "RIFF size field does not match the shortened payload")
    }

    // --------------------------------------------------------------- passthrough

    @Test
    fun unrecognisedPayload_isForwardedUntouched() {
        val mp4 = "0000ftypmp42somevideopayload".encodeToByteArray()
        assertContentEquals(mp4, scrub(mp4))
    }

    @Test
    fun emptyPayload_doesNotThrow() {
        assertContentEquals(ByteArray(0), scrub(ByteArray(0)))
    }

    @Test
    fun truncatedJpeg_isForwardedRatherThanCorrupted() {
        val truncated = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), APP1.toByte(), 0x7F, 0x7F,
            0x01, 0x02,
        )
        assertContentEquals(truncated, scrub(truncated))
    }

    // ------------------------------------------------------------------ helpers

    private fun buildJpeg(segments: List<Pair<Int, ByteArray>>, scanData: ByteArray): ByteArray {
        val out = Buffer()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { (marker, payload) ->
            val length = payload.size + 2
            out.write(byteArrayOf(0xFF.toByte(), marker.toByte()))
            out.write(byteArrayOf((length shr 8).toByte(), (length and 0xFF).toByte()))
            out.write(payload)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02))
        out.write(scanData)
        return out.readByteArray()
    }

    private fun buildPng(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val out = Buffer()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunks.forEach { (type, data) ->
            val n = data.size
            out.write(byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
            out.write(type.encodeToByteArray())
            out.write(data)
            out.write(ByteArray(4))
        }
        return out.readByteArray()
    }

    private fun buildWebp(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val body = Buffer()
        chunks.forEach { (fourCc, data) ->
            body.write(fourCc.encodeToByteArray())
            val n = data.size
            body.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
            body.write(data)
            if (n % 2 == 1) body.write(ByteArray(1))
        }
        val payload = body.readByteArray()
        val out = Buffer()
        out.write("RIFF".encodeToByteArray())
        val total = payload.size + 4
        out.write(
            byteArrayOf(total.toByte(), (total shr 8).toByte(), (total shr 16).toByte(), (total shr 24).toByte()),
        )
        out.write("WEBP".encodeToByteArray())
        out.write(payload)
        return out.readByteArray()
    }

    private fun ByteArray.containsAscii(needle: String): Boolean {
        val n = needle.encodeToByteArray()
        if (n.isEmpty() || size < n.size) return false
        for (i in 0..size - n.size) {
            var matched = true
            for (j in n.indices) {
                if (this[i + j] != n[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private fun ByteArray.endsWithBytes(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

    private companion object {
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val APP13 = 0xED
        const val COM = 0xFE
    }
}
