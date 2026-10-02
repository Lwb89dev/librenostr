package net.primal.android.emoji

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.EmojiPack
import net.primal.android.emoji.model.UserEmojiList
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind

/**
 * NIP-30 custom emoji: parsing and building the tags, nothing else.
 *
 * Kept free of Android and of any repository so the rules — what counts as a shortcode, which tags
 * are trusted, which are dropped — live in one place and are unit-testable on their own.
 */
object Nip30 {

    /**
     * NIP-30 limits shortcodes to alphanumerics and underscores; hyphens are accepted too, because
     * packs in the wild use them and every major client (Amethyst, wisp, Ditto) renders them.
     */
    private val SHORTCODE = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** A `:shortcode:` occurrence in note text. */
    val SHORTCODE_IN_TEXT = Regex(":([A-Za-z0-9_-]{1,64}):")

    fun isValidShortcode(shortcode: String): Boolean = SHORTCODE.matches(shortcode)

    /**
     * The emoji declared by a note's `emoji` tags, shortcode to URL. A tag with a malformed
     * shortcode or a non-web URL is ignored rather than trusted: the URL ends up in an image loader,
     * and a `file:` or `content:` URL from a stranger's note has no business there.
     */
    fun List<JsonArray>.customEmojis(): Map<String, String> =
        buildMap {
            for (tag in this@customEmojis) {
                val emoji = tag.asEmojiOrNull() ?: continue
                putIfAbsent(emoji.shortcode, emoji.url)
            }
        }

    fun parseEmojiPack(event: NostrEvent): EmojiPack? {
        val identifier = event.tags.valueOf("d")
        if (event.kind != NostrEventKind.EmojiSet.value || identifier == null) return null
        return EmojiPack(
            ownerPubkey = event.pubKey,
            identifier = identifier,
            title = event.tags.valueOf("title")?.takeIf { it.isNotBlank() }
                ?: event.tags.valueOf("name")?.takeIf { it.isNotBlank() }
                ?: identifier,
            emojis = event.tags.mapNotNull { it.asEmojiOrNull() }.distinctBy { it.shortcode },
            createdAt = event.createdAt,
        )
    }

    fun parseUserEmojiList(event: NostrEvent): UserEmojiList? {
        if (event.kind != NostrEventKind.UserEmojiList.value) return null
        val packPrefix = "${NostrEventKind.EmojiSet.value}:"
        return UserEmojiList(
            emojis = event.tags.mapNotNull { it.asEmojiOrNull() }.distinctBy { it.shortcode },
            packAddresses = event.tags
                .filter { it.name() == "a" }
                .mapNotNull { it.valueAt(1) }
                .filter { it.startsWith(packPrefix) }
                .distinct(),
            createdAt = event.createdAt,
        )
    }

    fun emojiTag(emoji: CustomEmoji): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("emoji"))
            add(JsonPrimitive(emoji.shortcode))
            add(JsonPrimitive(emoji.url))
        }

    fun emojiPackTags(identifier: String, title: String, emojis: List<CustomEmoji>): List<JsonArray> =
        buildList {
            add(stringTag("d", identifier))
            add(stringTag("title", title))
            emojis.forEach { add(emojiTag(it)) }
        }

    fun userEmojiListTags(list: UserEmojiList): List<JsonArray> =
        list.emojis.map { emojiTag(it) } + list.packAddresses.map { stringTag("a", it) }

    /**
     * The `emoji` tags a note needs: one per distinct `:shortcode:` in [content] that [available]
     * knows. Text that merely looks like a shortcode (a time like `12:30:45`, say) gets no tag and so
     * stays plain text for every reader, exactly as typed.
     */
    fun emojiTagsForContent(content: String, available: Map<String, String>): List<JsonArray> {
        if (available.isEmpty()) return emptyList()
        return SHORTCODE_IN_TEXT.findAll(content)
            .map { it.groupValues[1] }
            .distinct()
            .mapNotNull { shortcode -> available[shortcode]?.let { CustomEmoji(shortcode, it) } }
            .map { emojiTag(it) }
            .toList()
    }

    private fun JsonArray.asEmojiOrNull(): CustomEmoji? {
        val shortcode = valueAt(1)?.takeIf { isValidShortcode(it) }
        val url = valueAt(2)?.trim()?.takeIf { it.isWebUrl() }
        if (name() != "emoji" || shortcode == null || url == null) return null
        return CustomEmoji(shortcode = shortcode, url = url)
    }

    private fun String.isWebUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    private fun JsonArray.name(): String? = valueAt(0)

    private fun JsonArray.valueAt(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.contentOrNull

    private fun List<JsonArray>.valueOf(name: String): String? = firstOrNull { it.name() == name }?.valueAt(1)

    private fun stringTag(name: String, value: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(name))
            add(JsonPrimitive(value))
        }
}
