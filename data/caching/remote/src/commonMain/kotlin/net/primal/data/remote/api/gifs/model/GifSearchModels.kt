package net.primal.data.remote.api.gifs.model

/** Where a page of GIF results came from; shown in the picker's attribution line. */
enum class GifSource {
    /** nostr.build's index of GIFs uploaded to its free public pool: Nostr-native media. */
    NostrBuild,

    /** GIFverse, the open GIF index Ditto uses; the fallback while nostr.build refuses us. */
    Gifverse,
}

/**
 * One GIF, normalized across providers.
 *
 * @property url the file to put in the note. Posted as is — never re-hosted — so it must be a
 *   stable public URL (nostr.build's media host, or GIFverse's media endpoint).
 * @property previewUrl what the picker grid shows: a small animated WebP where the provider has
 *   one, so browsing the grid does not download dozens of multi-megabyte originals.
 * @property mimeType `image/gif` or `image/webp`, for the note's NIP-92 `imeta` tag.
 * @property sizeBytes the original's size, when the provider knows it.
 */
data class GifResult(
    val id: String,
    val url: String,
    val previewUrl: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long? = null,
    val title: String = "",
)

/** Continues a listing: the provider that produced it and where its next page starts. */
data class GifCursor(
    val source: GifSource,
    val offset: Int,
)

/**
 * One page of results.
 *
 * @property nextCursor where the following page starts, or null when this was the last one.
 */
data class GifSearchPage(
    val results: List<GifResult>,
    val source: GifSource,
    val nextCursor: GifCursor? = null,
)
