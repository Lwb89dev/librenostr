package net.primal.domain.nostr.cryptography

import fr.acinq.secp256k1.Hex
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.toHex

fun NostrEvent.toUnsignedEvent(): NostrUnsignedEvent =
    NostrUnsignedEvent(
        pubKey = pubKey,
        createdAt = createdAt,
        tags = tags,
        kind = kind,
        content = content,
    )

private const val MAX_EVENT_CONTENT_CHARS = 256 * 1024
private const val MAX_EVENT_TAGS = 2_000

fun NostrEvent.hasValidIdAndSignature(): Boolean {
    if (content.length > MAX_EVENT_CONTENT_CHARS || tags.size > MAX_EVENT_TAGS) return false
    return try {
        val calculatedId = toUnsignedEvent().calculateEventId().toHex()
        if (!id.equals(calculatedId, ignoreCase = true)) return false
        val signature = Hex.decode(sig)
        val pubKeyBytes = Hex.decode(pubKey)
        val idBytes = Hex.decode(id)
        CryptoUtils.verifySchnorr(
            signature = signature,
            hash = idBytes,
            pubKey = pubKeyBytes,
        )
    } catch (_: Throwable) {
        false
    }
}
