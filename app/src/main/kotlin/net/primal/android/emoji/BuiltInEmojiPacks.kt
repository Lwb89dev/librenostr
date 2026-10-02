package net.primal.android.emoji

import androidx.annotation.DrawableRes
import net.primal.android.R
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.EmojiPack

/**
 * Emoji packs that ship with the app: always in the picker, nothing to fetch or subscribe to.
 *
 * Two copies of each image, for two different readers:
 * - LibreNostr itself draws the bundled drawable ([localDrawableFor]), so the pack shows instantly
 *   and offline, in the picker and in every note that uses it;
 * - everyone else gets the URL in the note's `emoji` tag, which has to be a public web address
 *   another client can fetch. It points at the pack's PNGs in the project's public repository
 *   (`assets/emoji/librenostr-ostrich-pack/`), served by GitHub as `image/png` with CORS open, so
 *   web clients can draw it too.
 */
object BuiltInEmojiPacks {

    private const val OSTRICH_PACK_BASE_URL =
        "https://raw.githubusercontent.com/Lwb89dev/librenostr/main/assets/emoji/librenostr-ostrich-pack"

    private data class BuiltInEmoji(
        val shortcode: String,
        val fileName: String,
        @DrawableRes val drawable: Int,
    ) {
        val url: String get() = "$OSTRICH_PACK_BASE_URL/$fileName"
    }

    // Same names and files as assets/emoji/librenostr-ostrich-pack/manifest.json.
    private val ostrichEmojis = listOf(
        BuiltInEmoji("ostrich_happy", "ostrich-happy.png", R.drawable.librenostr_ostrich_happy),
        BuiltInEmoji("ostrich_love", "ostrich-love.png", R.drawable.librenostr_ostrich_love),
        BuiltInEmoji("ostrich_shocked", "ostrich-shocked.png", R.drawable.librenostr_ostrich_shocked),
        BuiltInEmoji("ostrich_thinking", "ostrich-thinking.png", R.drawable.librenostr_ostrich_thinking),
        BuiltInEmoji("ostrich_sleepy", "ostrich-sleepy.png", R.drawable.librenostr_ostrich_sleepy),
        BuiltInEmoji("ostrich_zap", "ostrich-zap.png", R.drawable.librenostr_ostrich_zap),
    )

    private val drawablesByUrl: Map<String, Int> = ostrichEmojis.associate { it.url to it.drawable }

    val ostrichPack = EmojiPack(
        ownerPubkey = "",
        identifier = "librenostr-ostrich",
        title = "LibreNostr Ostrich",
        emojis = ostrichEmojis.map { CustomEmoji(shortcode = it.shortcode, url = it.url) },
        isBuiltIn = true,
    )

    val all: List<EmojiPack> = listOf(ostrichPack)

    /**
     * The bundled image for a built-in emoji's public URL, or null for any other URL. Matched on
     * the exact URL, not the shortcode: someone else's `:ostrich_happy:` pointing elsewhere is their
     * image, and must be drawn as such.
     */
    @DrawableRes
    fun localDrawableFor(url: String): Int? = drawablesByUrl[url]
}
