package net.primal.data.repository.mappers.remote

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.detectUrls
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.messages.DirectMessageData
import net.primal.data.local.dao.messages.PrivateThreadReplyData
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.messages.Nip17Message
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

fun Nip17Message.hasPrivateThreadMarkers(): Boolean = tags.any { tag ->
    tag.getOrNull(0)?.jsonPrimitive?.content == "e" &&
        tag.getOrNull(3)?.jsonPrimitive?.content in setOf("root", "reply")
}

fun Nip17Message.mapAsPrivateThreadReplyPO(userId: String): PrivateThreadReplyData {
    require(content.toByteArray().size <= MAX_PRIVATE_REPLY_BYTES) { "Private reply is too large." }
    val rootId = threadEventId(marker = "root")
    val parentId = threadEventId(marker = "reply")
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

private fun Nip17Message.threadEventId(marker: String): String = tags.singleOrNull { tag ->
    tag.getOrNull(0)?.jsonPrimitive?.content == "e" &&
        tag.getOrNull(3)?.jsonPrimitive?.content == marker
}?.getOrNull(1)?.jsonPrimitive?.content ?: error("Missing or duplicate $marker marker.")

private fun String.isEventId(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private const val MAX_PRIVATE_REPLY_BYTES = 64 * 1024
