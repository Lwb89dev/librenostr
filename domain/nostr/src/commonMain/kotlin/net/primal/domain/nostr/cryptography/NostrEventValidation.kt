package net.primal.domain.nostr.cryptography

import fr.acinq.secp256k1.Hex
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
private const val MAX_EVENT_TAGS = 10_000

fun JsonObject.hasValidNip01Id(): Boolean {
    val claimedId = this["id"]?.jsonPrimitive?.content ?: return false
    val pubkey = this["pubkey"]?.jsonPrimitive?.content ?: return false
    val createdAt = this["created_at"]?.jsonPrimitive?.longOrNull
        ?: this["created_at"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: return false
    val kind = this["kind"]?.jsonPrimitive?.intOrNull
        ?: this["kind"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: return false
    val tags = this["tags"] ?: return false
    val content = this["content"]?.jsonPrimitive?.content ?: return false
    if (content.length > MAX_EVENT_CONTENT_CHARS) return false
    val tagCount = runCatching { tags.jsonArray.size }.getOrDefault(MAX_EVENT_TAGS + 1)
    if (tagCount > MAX_EVENT_TAGS) return false
    val payload = buildJsonArray {
        add(0)
        add(pubkey)
        add(createdAt)
        add(kind)
        add(tags)
        add(content)
    }
    val calculated = CryptoUtils.sha256(payload.toString().toByteArray()).toHex()
    return claimedId.equals(calculated, ignoreCase = true)
}

fun NostrEvent.hasValidSchnorrSignature(): Boolean {
    if (content.length > MAX_EVENT_CONTENT_CHARS || tags.size > MAX_EVENT_TAGS) return false
    return try {
        CryptoUtils.verifySchnorr(
            signature = Hex.decode(sig),
            hash = Hex.decode(id),
            pubKey = Hex.decode(pubKey),
        )
    } catch (_: Throwable) {
        false
    }
}

fun NostrEvent.hasValidIdAndSignature(): Boolean {
    if (content.length > MAX_EVENT_CONTENT_CHARS || tags.size > MAX_EVENT_TAGS) return false
    return try {
        val calculatedId = toUnsignedEvent().calculateEventId().toHex()
        if (!id.equals(calculatedId, ignoreCase = true)) return false
        hasValidSchnorrSignature()
    } catch (_: Throwable) {
        false
    }
}
