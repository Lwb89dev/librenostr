package net.primal.android.emoji

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.android.emoji.Nip30.customEmojis
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.UserEmojiList
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import org.junit.Test

class Nip30Test {

    private fun tag(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun event(kind: Int, tags: List<JsonArray>, createdAt: Long = 100) =
        NostrEvent(id = "id", pubKey = "pk", createdAt = createdAt, kind = kind, tags = tags, content = "", sig = "sig")

    @Test
    fun customEmojis_keepsOnlyWellFormedWebEmojiTags() {
        val tags = listOf(
            tag("emoji", "ostrich_happy", "https://example.com/a.png"),
            tag("emoji", "bad shortcode", "https://example.com/b.png"),
            tag("emoji", "local", "file:///sdcard/c.png"),
            tag("emoji", "short"),
            tag("p", "someone"),
            tag("emoji", "ostrich_happy", "https://example.com/duplicate.png"),
        )

        tags.customEmojis() shouldContainExactly mapOf("ostrich_happy" to "https://example.com/a.png")
    }

    @Test
    fun parseEmojiPack_readsIdentifierTitleAndEmojis() {
        val pack = Nip30.parseEmojiPack(
            event(
                kind = NostrEventKind.EmojiSet.value,
                tags = listOf(
                    tag("d", "birds"),
                    tag("title", "Birds"),
                    tag("emoji", "owl", "https://example.com/owl.png"),
                    tag("emoji", "duck", "https://example.com/duck.png"),
                ),
            ),
        )

        pack?.identifier shouldBe "birds"
        pack?.title shouldBe "Birds"
        pack?.address shouldBe "30030:pk:birds"
        pack?.emojis?.map { it.shortcode } shouldBe listOf("owl", "duck")
    }

    @Test
    fun parseEmojiPack_withoutDTag_isRejected() {
        val event = event(kind = NostrEventKind.EmojiSet.value, tags = listOf(tag("title", "x")))

        Nip30.parseEmojiPack(event) shouldBe null
    }

    @Test
    fun parseUserEmojiList_keepsOnlyEmojiSetReferences() {
        val list = Nip30.parseUserEmojiList(
            event(
                kind = NostrEventKind.UserEmojiList.value,
                tags = listOf(
                    tag("emoji", "wave", "https://example.com/wave.png"),
                    tag("a", "30030:pk:birds"),
                    tag("a", "30023:pk:an-article"),
                ),
            ),
        )

        list?.emojis shouldBe listOf(CustomEmoji("wave", "https://example.com/wave.png"))
        list?.packAddresses shouldBe listOf("30030:pk:birds")
    }

    @Test
    fun userEmojiListTags_roundTrip() {
        val list = UserEmojiList(
            emojis = listOf(CustomEmoji("wave", "https://example.com/wave.png")),
            packAddresses = listOf("30030:pk:birds"),
        )

        val parsed = Nip30.parseUserEmojiList(
            event(kind = NostrEventKind.UserEmojiList.value, tags = Nip30.userEmojiListTags(list)),
        )

        parsed?.emojis shouldBe list.emojis
        parsed?.packAddresses shouldBe list.packAddresses
    }

    @Test
    fun emojiTagsForContent_tagsEachKnownShortcodeOnce() {
        val available = mapOf(
            "ostrich_zap" to "https://example.com/zap.png",
            "owl" to "https://example.com/owl.png",
        )

        val tags = Nip30.emojiTagsForContent(
            content = "gm :ostrich_zap: :ostrich_zap: at 12:30:45 :unknown: :owl:",
            available = available,
        )

        tags.map { it[1].toString().trim('"') } shouldContainExactly listOf("ostrich_zap", "owl")
    }

    @Test
    fun builtInPack_mapsItsPublicUrlsToBundledDrawables() {
        BuiltInEmojiPacks.ostrichPack.emojis.forEach { emoji ->
            (BuiltInEmojiPacks.localDrawableFor(emoji.url) != null) shouldBe true
        }
        BuiltInEmojiPacks.localDrawableFor("https://example.com/ostrich-happy.png") shouldBe null
    }
}
