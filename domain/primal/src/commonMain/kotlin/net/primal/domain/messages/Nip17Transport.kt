package net.primal.domain.messages

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray

/** A validated and decrypted NIP-17 rumor. The outer Gift Wrap never crosses this boundary. */
data class Nip17Message(
    val eventId: String,
    val outerEventId: String,
    val senderId: String,
    val recipientIds: List<String>,
    val createdAt: Long,
    val content: String,
    val tags: List<JsonArray>,
)

/**
 * Modern private-message transport. Implementations own NIP-44/NIP-59 and DM-relay routing;
 * repositories only classify already validated plaintext into ordinary DMs or private replies.
 */
interface Nip17Transport {
    suspend fun sendMessage(
        userId: String,
        receiverId: String,
        content: String,
        extraTags: List<JsonArray> = emptyList(),
    ): Nip17Message

    suspend fun fetchMessages(userId: String, limit: Int = DEFAULT_FETCH_LIMIT): List<Nip17Message>

    fun subscribeMessages(userId: String): Flow<Nip17Message>

    companion object {
        const val DEFAULT_FETCH_LIMIT = 500
    }
}

class Nip17RelayListNotFoundException(val recipientId: String) :
    IllegalStateException("No NIP-17 DM relay list was found for $recipientId.")
