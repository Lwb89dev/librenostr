package net.primal.data.account.signer.remote.model

import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import net.primal.domain.nostr.NostrUnsignedEvent

@Serializable
data class NostrUnsignedEventNoPubkey(
    @SerialName("created_at") val createdAt: Long = Clock.System.now().epochSeconds,
    val tags: List<JsonArray> = emptyList(),
    val kind: Int,
    val content: String,
)

fun NostrUnsignedEventNoPubkey.withPubKey(pubkey: String) =
    NostrUnsignedEvent(
        pubKey = pubkey,
        createdAt = createdAt,
        tags = tags,
        kind = kind,
        content = content,
    )

/** The NIP-46 `sign_event` wire shape: a bunker fills in the pubkey itself, so it isn't sent. */
fun NostrUnsignedEvent.withoutPubKey() =
    NostrUnsignedEventNoPubkey(
        createdAt = createdAt,
        tags = tags,
        kind = kind,
        content = content,
    )
