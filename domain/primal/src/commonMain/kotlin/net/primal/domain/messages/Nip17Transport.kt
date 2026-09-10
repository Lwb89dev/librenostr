package net.primal.domain.messages

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray

/** A validated and decrypted NIP-17 rumor. The outer Gift Wrap never crosses this boundary. */
data class Nip17Message(
    val eventId: String,
    val outerEventId: String,
    /**
     * The rumor's own kind, which is what says whether this is a chat message or a thread reply.
     *
     * Kind 14 is a NIP-17 direct message; kind 1 is a gift-wrapped text note — a private reply or
     * private post, the same shape Amethyst's `NIP17Factory.createNoteNIP17` produces. Classifying
     * on tag shapes instead got both wrong: a chat message that quotes a note carries `e` tags too.
     */
    val kind: Int,
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

    /**
     * Sends a private thread note. Unlike a direct message (kind 14), the encrypted rumor is a
     * normal text note (kind 1) whose thread tags are kept inside the NIP-59 envelope.
     */
    suspend fun sendPrivateReply(
        userId: String,
        receiverId: String,
        content: String,
        threadTags: List<JsonArray>,
    ): Nip17Message

    /**
     * The relays a private event addressed to [userId] has to be delivered to.
     *
     * Exposed so the legacy kind-4 path can reach the same inboxes instead of publishing only to
     * the sender's own write relays: two accounts with disjoint relay sets could otherwise send
     * each other messages that neither would ever fetch, which reads from outside as "sent, never
     * arrived". Resolution follows NIP-17's kind-10050, then NIP-65 read relays, then a bootstrap
     * pool — the same order Amethyst's `DmActions.resolveDmRelays` uses in permissive mode.
     */
    suspend fun resolveInboxRelays(userId: String): List<String>

    suspend fun fetchMessages(userId: String, limit: Int = DEFAULT_FETCH_LIMIT): List<Nip17Message>

    fun subscribeMessages(userId: String): Flow<Nip17Message>

    companion object {
        const val DEFAULT_FETCH_LIMIT = 500
    }
}

class Nip17RelayListNotFoundException(val recipientId: String) :
    IllegalStateException("No NIP-17 DM relay list was found for $recipientId.")
