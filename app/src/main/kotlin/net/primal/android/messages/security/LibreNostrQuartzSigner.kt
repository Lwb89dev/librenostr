package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip57Zaps.LnZapPrivateEvent
import com.vitorpamplona.quartz.nip57Zaps.LnZapRequestEvent
import kotlinx.serialization.json.JsonPrimitive
import net.primal.android.nostr.notary.NostrNotary
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.utils.unwrapOrThrow

/** Adapts LibreNostr's local/Amber/NIP-46 signer abstraction to Quartz's NIP-17 factory. */
class LibreNostrQuartzSigner(
    pubKey: String,
    private val notary: NostrNotary,
) : NostrSigner(pubKey) {

    override fun isWriteable(): Boolean = true

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> sign(
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
    ): T {
        val signed = notary.signNostrEvent(
            NostrUnsignedEvent(
                pubKey = pubKey,
                createdAt = createdAt,
                kind = kind,
                tags = tags.map { tag -> tag.toJsonArray() },
                content = content,
            ),
        ).unwrapOrThrow()
        // Quartz 1.04.2's generic Event deserializer has no kind-13 registration and throws an
        // NPE for NIP-59 seals. The signer must return the concrete type requested by Quartz,
        // especially while SealedRumorEvent.create is building a NIP-17 envelope.
        return signed.asQuartzEvent() as T
    }

    override suspend fun nip44Encrypt(plaintext: String, pubKey: String): String =
        notary.nip44Encrypt(userId = this.pubKey, participantId = pubKey, plaintext = plaintext)

    override suspend fun nip44Decrypt(ciphertext: String, pubKey: String): String =
        notary.nip44Decrypt(userId = this.pubKey, participantId = pubKey, ciphertext = ciphertext)

    override suspend fun nip04Encrypt(plaintext: String, pubKey: String): String =
        unsupported("NIP-04 encryption")

    override suspend fun nip04Decrypt(ciphertext: String, pubKey: String): String =
        unsupported("NIP-04 decryption")

    override suspend fun decryptZapEvent(event: LnZapRequestEvent): LnZapPrivateEvent =
        unsupported("private zap decryption")

    override suspend fun deriveKey(nonce: String): String = unsupported("key derivation")

    override fun hasForegroundSupport(): Boolean = true

    private fun <T> unsupported(operation: String): T =
        throw UnsupportedOperationException("$operation is not used by the NIP-17 transport.")
}

private fun NostrEvent.asQuartzEvent(): Event {
    val quartzTags = tags.map { tag ->
        tag.map { element -> (element as JsonPrimitive).content }.toTypedArray()
    }.toTypedArray()
    return when (kind) {
        NostrEventKind.PrivateDirectMessage.value -> ChatMessageEvent(
            id = id,
            pubKey = pubKey,
            createdAt = createdAt,
            tags = quartzTags,
            content = content,
            sig = sig,
        )

        NostrEventKind.ShortTextNote.value -> TextNoteEvent(
            id = id,
            pubKey = pubKey,
            createdAt = createdAt,
            tags = quartzTags,
            content = content,
            sig = sig,
        )

        NostrEventKind.SealedRumor.value -> SealedRumorEvent(
            id = id,
            pubKey = pubKey,
            createdAt = createdAt,
            tags = quartzTags,
            content = content,
            sig = sig,
        )

        else -> Event(
            id = id,
            pubKey = pubKey,
            createdAt = createdAt,
            kind = kind,
            tags = quartzTags,
            content = content,
            sig = sig,
        )
    }
}

private fun Array<String>.toJsonArray() = kotlinx.serialization.json.buildJsonArray {
    forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
}
