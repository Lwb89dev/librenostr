package net.primal.android.nostr.notary

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.parseIncomingMessage
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.cryptography.hasValidIdAndSignature
import net.primal.domain.nostr.cryptography.hasValidNip01Id
import net.primal.domain.nostr.cryptography.signOrThrow
import net.primal.domain.nostr.cryptography.utils.toHex
import net.primal.domain.nostr.serialization.toNostrJsonObject
import org.junit.Test

class NostrUnsignedEventExtTest {

    companion object {
        private const val TEST_NSEC = "nsec18c2dg4s9j7ndlujesf4fq5m3ty6u92jpqffuckf75xyyxqsqy4pstyzq4l"
    }

    @Test
    fun `calculateEventId returns correct id`() {
        val expectedId = "ff1b43e518c16c13ab78ec54b11fb462877822f4596d814efa0e1ab6714c402f"
        val unsignedEvent = NostrUnsignedEvent(
            content = "{\"description\":\"Sync app settings\"}",
            kind = 30078,
            tags = listOf(
                buildJsonArray {
                    add("d")
                    add("Primal-Android App")
                },
            ),
            createdAt = 1687881599,
            pubKey = "9b46c3f4a8dcdafdfff12a97c59758f38ff55002370fcfa7d14c8c857e9b5812",
        )

        val actualId = unsignedEvent.calculateEventId().toHex()
        actualId shouldBe expectedId
    }

    @Test
    fun `sign returns correct signature`() {
        val expectedSig = "14e5c298d639ab4285452707d8e6b212e4554b8381bb0a312fa87d6bf5" +
            "ae954bfadf13a9a69b9b5aac3dd37ea6d6020e07ae860e686fce804a408f49ce3c9765"
        val unsignedEvent = NostrUnsignedEvent(
            content = "{\"description\":\"Sync app settings\"}",
            kind = 30078,
            tags = listOf(
                buildJsonArray {
                    add("d")
                    add("Primal-Android App")
                },
            ),
            createdAt = 1687881599,
            pubKey = "9b46c3f4a8dcdafdfff12a97c59758f38ff55002370fcfa7d14c8c857e9b5812",
        )

        val signedEvent = unsignedEvent.signOrThrow(
            "nsec18c2dg4s9j7ndlujesf4fq5m3ty6u92jpqffuckf75xyyxqsqy4pstyzq4l",
        )
        signedEvent.sig shouldBe expectedSig
    }

    @Test
    fun `valid signed event passes id and signature check`() {
        val signedEvent = unsignedFixture().signOrThrow(TEST_NSEC)
        signedEvent.hasValidIdAndSignature() shouldBe true
    }

    @Test
    fun `tampered content fails signature check`() {
        val signedEvent = unsignedFixture().signOrThrow(TEST_NSEC)
        signedEvent.copy(content = "tampered").hasValidIdAndSignature() shouldBe false
    }

    @Test
    fun `signed note survives incoming EVENT parse`() {
        val signedEvent = unsignedFixture().signOrThrow(TEST_NSEC)
        signedEvent.toNostrJsonObject().hasValidNip01Id() shouldBe true

        val message = buildJsonArray {
            add("EVENT")
            add("sub-1")
            add(signedEvent.toNostrJsonObject())
        }
        val parsed = message.toString().parseIncomingMessage()
        parsed should beInstanceOf<NostrIncomingMessage.EventMessage>()
        val incoming = parsed as NostrIncomingMessage.EventMessage
        incoming.nostrEvent.shouldNotBeNull()
        incoming.nostrEvent?.id shouldBe signedEvent.id
    }

    private fun unsignedFixture() =
        NostrUnsignedEvent(
            content = "{\"description\":\"Sync app settings\"}",
            kind = 30078,
            tags = listOf(
                buildJsonArray {
                    add("d")
                    add("Primal-Android App")
                },
            ),
            createdAt = 1687881599,
            pubKey = "9b46c3f4a8dcdafdfff12a97c59758f38ff55002370fcfa7d14c8c857e9b5812",
        )
}
