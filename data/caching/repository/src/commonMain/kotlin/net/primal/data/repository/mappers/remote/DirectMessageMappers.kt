package net.primal.data.repository.mappers.remote

import io.github.aakira.napier.Napier
import net.primal.core.utils.detectUrls
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.messages.DirectMessageData
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.findFirstProfileId
import net.primal.domain.nostr.utils.parseHashtags
import net.primal.domain.nostr.utils.parseNostrUris
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
        uris = (decryptedMessage.detectUrls() + decryptedMessage.parseNostrUris()).asEncryptable(),
        hashtags = decryptedMessage.parseHashtags().asEncryptable(),
    )
}
