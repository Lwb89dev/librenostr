package net.primal.android.emoji.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import net.primal.android.core.compose.PrimalAsyncImage
import net.primal.android.emoji.BuiltInEmojiPacks

/** The inline-content id a `:shortcode:` placeholder in note text is drawn by. */
fun customEmojiInlineContentId(shortcode: String): String = "nip30:$shortcode"

/**
 * A custom emoji image: the bundled copy when it is one of the app's own (no network, works
 * offline), the URL otherwise. Fit, never cropped — emoji art is drawn to its own square, and
 * cropping a wide one would cut it.
 */
@Composable
fun CustomEmojiImage(
    url: String,
    shortcode: String,
    modifier: Modifier = Modifier,
) {
    PrimalAsyncImage(
        model = BuiltInEmojiPacks.localDrawableFor(url) ?: url,
        contentDescription = ":$shortcode:",
        contentScale = ContentScale.Fit,
        placeholderColor = Color.Transparent,
        placeHolderHighlight = null,
        modifier = modifier,
    )
}

/**
 * The inline content that draws every custom emoji a note declares, keyed like the placeholders
 * `toAnnotatedString` emits. A little larger than the text (1.3 em) so detailed emoji art stays
 * legible, centred on the line so it does not push the next line down more than necessary.
 */
@Composable
fun rememberCustomEmojiInlineContent(customEmojis: Map<String, String>): Map<String, InlineTextContent> =
    remember(customEmojis) {
        customEmojis.entries.associate { (shortcode, url) ->
            customEmojiInlineContentId(shortcode) to InlineTextContent(
                placeholder = Placeholder(
                    width = EMOJI_SIZE_EM.em,
                    height = EMOJI_SIZE_EM.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                CustomEmojiImage(url = url, shortcode = shortcode, modifier = Modifier.fillMaxSize())
            }
        }
    }

private const val EMOJI_SIZE_EM = 1.3f
