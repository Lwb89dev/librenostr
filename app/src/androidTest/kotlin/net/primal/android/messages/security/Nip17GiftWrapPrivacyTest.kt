package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test

class Nip17GiftWrapPrivacyTest {

    @Test
    fun giftWrap_exposesNeitherPlaintextNorThreadRelationship() {
        runBlocking {
            val sender = NostrSignerInternal(KeyPair())
            val recipient = NostrSignerInternal(KeyPair())
            val outer = privateReplyWrap(template(recipient.pubKey), sender, recipient.pubKey)

            outer.kind shouldBe 1059
            outer.tags.all { it.firstOrNull() == "p" } shouldBe true
            outer.content.contains(PLAINTEXT) shouldBe false
            outer.content.contains(ROOT) shouldBe false
            outer.content.contains(PARENT) shouldBe false

            val seal = outer.unwrapThrowing(recipient) as SealedRumorEvent
            val rumor = seal.unsealThrowing(recipient)
            rumor.kind shouldBe 1
            rumor.content shouldBe PLAINTEXT
            rumor.tags.any { it.getOrNull(1) == ROOT && it.getOrNull(3) == "root" } shouldBe true
            rumor.tags.any { it.getOrNull(1) == PARENT && it.getOrNull(3) == "reply" } shouldBe true
        }
    }

    @Test
    fun unrelatedSigner_cannotUnwrapRecipientGiftWrap() {
        runBlocking {
            val sender = NostrSignerInternal(KeyPair())
            val recipient = NostrSignerInternal(KeyPair())
            val unrelated = NostrSignerInternal(KeyPair())
            val outer = privateReplyWrap(template(recipient.pubKey), sender, recipient.pubKey)

            outer.unwrapOrNull(unrelated) shouldBe null
        }
    }

    private fun template(recipient: String) = EventTemplate<TextNoteEvent>(
        createdAt = 1_700_000_000,
        kind = 1,
        tags = arrayOf(
            arrayOf("p", recipient),
            arrayOf("e", ROOT, "", "root"),
            arrayOf("e", PARENT, "", "reply"),
        ),
        content = PLAINTEXT,
    )

    private suspend fun privateReplyWrap(
        template: EventTemplate<TextNoteEvent>,
        sender: NostrSignerInternal,
        recipient: String,
    ): GiftWrapEvent {
        val rumor = sender.sign<TextNoteEvent>(template)
        val seal = SealedRumorEvent.create(event = rumor, encryptTo = recipient, signer = sender)
        return GiftWrapEvent.create(event = seal, recipientPubKey = recipient)
    }

    private companion object {
        const val PLAINTEXT = "private reply text"
        val ROOT = "1".repeat(64)
        val PARENT = "2".repeat(64)
    }
}
