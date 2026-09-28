package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.android.networking.relays.RelayPoolQueryResult
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.notary.NostrNotary
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayFilter
import org.junit.Test

/**
 * Covers the fix for opening a DM conversation blocking the main thread while re-decrypting the
 * whole inbox: `fetchMessages` must not re-run NIP-44 decryption for a Gift Wrap it has already
 * unwrapped successfully, even though the same wrap keeps coming back from every poll (relays
 * have no notion of "already delivered"). [NostrNotary.nip44Decrypt] is mocked to delegate to a
 * real Quartz signer, so decrypt-call counts here reflect genuine cryptographic work, not a stub.
 *
 * Instrumented, like the sibling [Nip17GiftWrapPrivacyTest]: real NIP-44/secp256k1 crypto needs
 * the native library that is only loaded on an Android runtime, not a JVM `app/src/test` shard.
 */
class Nip17TransportImplTest {

    private val sender = NostrSignerInternal(KeyPair())
    private val recipient = NostrSignerInternal(KeyPair())
    private val recipientId = recipient.pubKey

    private fun realGiftWrapToRecipient(content: String): NostrEvent =
        runBlocking {
            val rumor = sender.sign<ChatMessageEvent>(
                EventTemplate(
                    createdAt = 1_700_000_000,
                    kind = NostrEventKind.PrivateDirectMessage.value,
                    tags = arrayOf(arrayOf("p", recipientId)),
                    content = content,
                ),
            )
            val seal = SealedRumorEvent.create(event = rumor, encryptTo = recipientId, signer = sender)
            GiftWrapEvent.create(event = seal, recipientPubKey = recipientId).asDomainEvent()
        }

    private fun com.vitorpamplona.quartz.nip01Core.core.Event.asDomainEvent() = NostrEvent(
        id = id,
        pubKey = pubKey,
        createdAt = createdAt,
        kind = kind,
        tags = tags.map { tag -> buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } } },
        content = content,
        sig = sig,
    )

    /** A [NostrNotary] whose NIP-44 calls are real, delegated to [recipient]'s Quartz signer. */
    private fun recipientNotary(): NostrNotary =
        mockk(relaxed = true) {
            coEvery { nip44Decrypt(userId = any(), participantId = any(), ciphertext = any()) } coAnswers {
                recipient.nip44Decrypt(ciphertext = thirdArg(), fromPublicKey = secondArg())
            }
        }

    private fun relaysReturning(vararg giftWraps: NostrEvent): RelaysSocketManager =
        mockk(relaxed = true) {
            coEvery { query(any()) } returns emptyList()
            coEvery { configuredUserRelays(any()) } returns emptyList()
            coEvery { queryEvents(filter = any<RelayFilter>(), relays = any()) } returns
                RelayPoolQueryResult(events = giftWraps.toList())
        }

    @Test
    fun fetchMessages_decryptsANewGiftWrap() = runBlocking {
        val wrap = realGiftWrapToRecipient("hello there")
        val notary = recipientNotary()
        val transport = Nip17TransportImpl(
            relays = relaysReturning(wrap),
            notary = notary,
            dispatcherProvider = createDispatcherProvider(),
        )

        val messages = transport.fetchMessages(userId = recipientId, limit = 10)

        messages.map { it.content } shouldBe listOf("hello there")
        // One unwrap of the outer envelope, one unseal of the rumor inside it.
        coVerify(exactly = 2) { notary.nip44Decrypt(userId = any(), participantId = any(), ciphertext = any()) }
    }

    @Test
    fun fetchMessages_secondPollOfTheSameWrap_doesNotDecryptAgain() = runBlocking {
        val wrap = realGiftWrapToRecipient("hello there")
        val notary = recipientNotary()
        val transport = Nip17TransportImpl(
            relays = relaysReturning(wrap),
            notary = notary,
            dispatcherProvider = createDispatcherProvider(),
        )

        val first = transport.fetchMessages(userId = recipientId, limit = 10)
        val second = transport.fetchMessages(userId = recipientId, limit = 10)

        first.map { it.content } shouldBe listOf("hello there")
        // Already delivered once and persisted by the caller; the relay has no way to stop
        // handing it back, so the transport itself must be the one that stops re-decrypting it.
        second shouldBe emptyList()
        coVerify(exactly = 2) { notary.nip44Decrypt(userId = any(), participantId = any(), ciphertext = any()) }
    }

    @Test
    fun fetchMessages_decryptsMultipleGiftWrapsConcurrently() = runBlocking {
        val wraps = (1..5).map { realGiftWrapToRecipient("message $it") }
        val notary = recipientNotary()
        val transport = Nip17TransportImpl(
            relays = relaysReturning(*wraps.toTypedArray()),
            notary = notary,
            dispatcherProvider = createDispatcherProvider(),
        )

        val messages = transport.fetchMessages(userId = recipientId, limit = 10)

        messages.map { it.content }.toSet() shouldBe (1..5).map { "message $it" }.toSet()
        coVerify(exactly = 10) { notary.nip44Decrypt(userId = any(), participantId = any(), ciphertext = any()) }
    }
}
