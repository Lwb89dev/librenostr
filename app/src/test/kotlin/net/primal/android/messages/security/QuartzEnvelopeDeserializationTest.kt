package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Pins the one assumption every incoming private message rests on.
 *
 * `Nip17TransportImpl.unwrap` parses a relay's JSON with Quartz's generic [Event.fromJson] and then
 * casts the result to [GiftWrapEvent]. If Quartz's factory has no entry for a kind it hands back a
 * plain [Event], that cast throws, and because the whole unwrap runs inside a `runCatching` every
 * gift wrap would be dropped in silence — every DM and private reply looking like it was never
 * sent. Nothing else covers this: the existing gift-wrap test builds its envelopes in memory and
 * never parses one off the wire.
 */
class QuartzEnvelopeDeserializationTest {

    @Test
    fun `a kind 1059 gift wrap parses as a GiftWrapEvent`() {
        val parsed = Event.fromJson(eventJson(kind = 1059, tags = """[["p","${"b".repeat(64)}"]]"""))

        withClue("Quartz parsed the gift wrap as ${parsed::class.simpleName}") {
            (parsed is GiftWrapEvent) shouldBe true
        }
    }

    @Test
    fun `a kind 13 seal parses as a SealedRumorEvent`() {
        val parsed = Event.fromJson(eventJson(kind = 13, tags = "[]"))

        withClue("Quartz parsed the seal as ${parsed::class.simpleName}") {
            (parsed is SealedRumorEvent) shouldBe true
        }
    }

    @Test
    fun `a kind 14 chat message parses as a ChatMessageEvent`() {
        val parsed = Event.fromJson(eventJson(kind = 14, tags = """[["p","${"b".repeat(64)}"]]"""))

        withClue("Quartz parsed the chat message as ${parsed::class.simpleName}") {
            (parsed is ChatMessageEvent) shouldBe true
        }
    }

    private fun eventJson(kind: Int, tags: String) =
        """
        {
          "id": "${"0".repeat(64)}",
          "pubkey": "${"a".repeat(64)}",
          "created_at": 1700000000,
          "kind": $kind,
          "tags": $tags,
          "content": "ciphertext",
          "sig": "${"c".repeat(128)}"
        }
        """.trimIndent()
}
