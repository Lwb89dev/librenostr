package net.primal.android.emoji.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A NIP-30 custom emoji: `:shortcode:` in a note's text, drawn as the image at [url].
 *
 * The note itself carries the mapping in an `["emoji", shortcode, url]` tag, so a reader never needs
 * the author's packs to render it — only the author needs them, to know what to write.
 */
@Immutable
@Serializable
data class CustomEmoji(
    val shortcode: String,
    val url: String,
)

/**
 * A named collection of custom emoji: a NIP-30 emoji set (kind 30030), or one of the packs bundled
 * with the app.
 *
 * @property ownerPubkey the author of the kind 30030 event; empty for a built-in pack.
 * @property identifier the event's `d` tag: together with the owner and the kind it is the pack's
 *   stable address, which is what a user's emoji list (kind 10030) points at.
 */
@Immutable
@Serializable
data class EmojiPack(
    val ownerPubkey: String,
    val identifier: String,
    val title: String,
    val emojis: List<CustomEmoji>,
    val createdAt: Long = 0,
    val isBuiltIn: Boolean = false,
) {
    /** The NIP-01 address (`30030:<pubkey>:<d>`) other events use to refer to this pack. */
    val address: String get() = emojiPackAddress(ownerPubkey = ownerPubkey, identifier = identifier)
}

fun emojiPackAddress(ownerPubkey: String, identifier: String): String = "30030:$ownerPubkey:$identifier"

/**
 * The user's emoji list (NIP-30 kind 10030): emoji added one by one, plus the packs picked whole,
 * by address.
 */
@Serializable
data class UserEmojiList(
    val emojis: List<CustomEmoji> = emptyList(),
    val packAddresses: List<String> = emptyList(),
    val createdAt: Long = 0,
)
