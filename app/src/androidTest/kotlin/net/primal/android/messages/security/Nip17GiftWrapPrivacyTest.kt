package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test

class Nip17GiftWrapPrivacyTest {

    @Test
    fun `gift wrap exposes neither plaintext nor thread relationship`() {
        runBlocking {
            val sender = NostrSignerInternal(KeyPair())
            val recipient = NostrSignerInternal(KeyPair())
            val result = NIP17Factory().createMessageNIP17(template(recipient.pubKey), sender)
            val outer = result.wraps.single { it.recipientPubKey() == recipient.pubKey }

            outer.kind shouldBe 1059
            outer.tags.all { it.firstOrNull() == "p" } shouldBe true
            outer.content.contains(PLAINTEXT) shouldBe false
            outer.content.contains(ROOT) shouldBe false
            outer.content.contains(PARENT) shouldBe false

            val seal = outer.unwrapThrowing(recipient) as SealedRumorEvent
            val rumor = seal.unsealThrowing(recipient)
            rumor.content shouldBe PLAINTEXT
            rumor.tags.any { it.getOrNull(1) == ROOT && it.getOrNull(3) == "root" } shouldBe true
            rumor.tags.any { it.getOrNull(1) == PARENT && it.getOrNull(3) == "reply" } shouldBe true
        }
    }

    @Test
    fun `unrelated signer cannot unwrap recipient gift wrap`() {
        runBlocking {
            val sender = NostrSignerInternal(KeyPair())
            val recipient = NostrSignerInternal(KeyPair())
            val unrelated = NostrSignerInternal(KeyPair())
            val outer = NIP17Factory().createMessageNIP17(template(recipient.pubKey), sender)
                .wraps.single { it.recipientPubKey() == recipient.pubKey }

            outer.unwrapOrNull(unrelated) shouldBe null
        }
    }

    private fun template(recipient: String) = EventTemplate<ChatMessageEvent>(
        createdAt = 1_700_000_000,
        kind = 14,
        tags = arrayOf(
            arrayOf("p", recipient),
            arrayOf("e", ROOT, "", "root"),
            arrayOf("e", PARENT, "", "reply"),
        ),
        content = PLAINTEXT,
    )

    private companion object {
        const val PLAINTEXT = "private reply text"
        val ROOT = "1".repeat(64)
        val PARENT = "2".repeat(64)
    }
}
