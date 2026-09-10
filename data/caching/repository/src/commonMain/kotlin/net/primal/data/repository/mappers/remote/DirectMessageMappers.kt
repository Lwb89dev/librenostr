package net.primal.data.repository.mappers.remote

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.detectUrls
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.messages.DirectMessageData
import net.primal.data.local.dao.messages.PrivateThreadReplyData
import net.primal.domain.messages.Nip17Message
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstProfileId
import net.primal.domain.nostr.utils.parseHashtags
import net.primal.domain.nostr.utils.parseNostrUriCandidates
import net.primal.shared.data.local.encryption.asEncryptable

fun List<NostrEvent>.mapAsMessageDataPO(
    userId: String,
    onMessageDecrypt: (userId: String, participantId: String, content: String) -> String,
) = mapNotNull { event ->
    // Relay history can contain malformed or differently encrypted kind-4 events. They must
    // not prevent valid messages from being persisted and displayed.
    val result = runCatching {
        event.mapAsMessageDataPO(userId = userId, onMessageDecrypt = onMessageDecrypt)
    }
    result.exceptionOrNull()?.let { error ->
        Napier.w(error) { "Skipping undecryptable direct message ${event.id}." }
    }
    result.getOrNull()
}

fun NostrEvent.mapAsMessageDataPO(
    userId: String,
    onMessageDecrypt: (userId: String, participantId: String, content: String) -> String,
): DirectMessageData? {
    val senderId = this.pubKey
    val receiverId = this.tags.findFirstProfileId() ?: return null
    val participantId = if (senderId != userId) senderId else receiverId

    val decryptedMessage = onMessageDecrypt(userId, participantId, this.content)

    return DirectMessageData(
        ownerId = userId,
        messageId = this.id,
        senderId = senderId,
        receiverId = receiverId,
        participantId = participantId,
        createdAt = this.createdAt,
        content = decryptedMessage.asEncryptable(),
        uris = (decryptedMessage.detectUrls() + decryptedMessage.parseNostrUriCandidates()).asEncryptable(),
        hashtags = decryptedMessage.parseHashtags().asEncryptable(),
    )
}

fun Nip17Message.mapAsMessageDataPO(userId: String): DirectMessageData? {
    val receiverId = recipientIds.firstOrNull { it != senderId } ?: return null
    val participantId = if (senderId == userId) receiverId else senderId
    return DirectMessageData(
        ownerId = userId,
        messageId = eventId,
        senderId = senderId,
        receiverId = receiverId,
        participantId = participantId,
        createdAt = createdAt,
        content = content.asEncryptable(),
        uris = (content.detectUrls() + content.parseNostrUriCandidates()).asEncryptable(),
        hashtags = content.parseHashtags().asEncryptable(),
    )
}

/**
 * True when this gift-wrapped event is a reply into a thread rather than a chat message.
 *
 * A NIP-17 chat message (kind 14) carries no `e` tag at all in the common case; a private reply —
 * Amethyst's `createNoteNIP17`, and what this app sends — is a kind-1 rumor carrying the same
 * NIP-10 `e` tags a public reply would. Any `e` tag is therefore the discriminator, not just a
 * marked one: NIP-10 lets a reply directly to a thread root carry a single `root`-marked tag, and
 * the deprecated positional form carries markerless tags, so requiring a marker classified both as
 * direct messages and dropped them out of every thread.
 */
fun Nip17Message.isPrivateThreadReply(): Boolean =
    kind == NostrEventKind.ShortTextNote.value && tags.any { it.isEventIdTag() }

fun Nip17Message.mapAsPrivateThreadReplyPO(userId: String): PrivateThreadReplyData {
    require(content.toByteArray().size <= MAX_PRIVATE_REPLY_BYTES) { "Private reply is too large." }
    val rootId = threadRootEventId() ?: error("Private reply names no thread root.")
    // NIP-10 allows a reply directly to the thread root to carry only the `root` marker. Treating
    // that as malformed dropped every such reply — the most common shape there is — on the floor.
    val parentId = threadParentEventId() ?: rootId
    require(rootId.isEventId() && parentId.isEventId()) { "Malformed private thread relationship." }
    require(eventId != rootId && eventId != parentId) { "A private reply cannot reference itself." }
    val recipientId = recipientIds.firstOrNull { it != senderId }
        ?: error("Private reply has no recipient.")
    require(userId == senderId || userId == recipientId) { "Private reply belongs to another user." }

    return PrivateThreadReplyData(
        ownerId = userId,
        eventId = eventId,
        outerEventId = outerEventId,
        senderId = senderId,
        recipientId = recipientId,
        createdAt = createdAt,
        content = content.asEncryptable(),
        rootId = rootId.asEncryptable(),
        parentId = parentId.asEncryptable(),
    )
}

/**
 * The conversation root this private reply belongs to, read exactly the way [threadRootId] reads a
 * public reply: the `root`-marked `e` tag, or the FIRST bare `e` tag for the deprecated positional
 * convention. Duplicates take the first match instead of erroring — a second `root` marker is a
 * malformed tag set from some other client, not a reason to hide the message from its recipient.
 */
private fun Nip17Message.threadRootEventId(): String? =
    tags.firstOrNull { it.isEventIdTag() && it.marker() == "root" }?.eventId()
        ?: tags.filterNot { it.marker() == "mention" }.firstOrNull { it.isEventIdTag() }?.eventId()

/**
 * The single event this private reply answers, read the way [immediateParentId] reads a public
 * reply: the `reply`-marked `e` tag, else the LAST bare `e` tag. Null when the reply names only a
 * root, which NIP-10 permits and the caller resolves to the root itself.
 */
private fun Nip17Message.threadParentEventId(): String? =
    tags.firstOrNull { it.isEventIdTag() && it.marker() == "reply" }?.eventId()
        ?: tags.filterNot { it.marker() == "mention" || it.marker() == "root" }
            .lastOrNull { it.isEventIdTag() }?.eventId()

private fun JsonArray.isEventIdTag(): Boolean = getOrNull(0)?.jsonPrimitive?.content == "e"

private fun JsonArray.marker(): String? = getOrNull(3)?.jsonPrimitive?.content

private fun JsonArray.eventId(): String? = getOrNull(1)?.jsonPrimitive?.content

private fun String.isEventId(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private const val MAX_PRIVATE_REPLY_BYTES = 64 * 1024
