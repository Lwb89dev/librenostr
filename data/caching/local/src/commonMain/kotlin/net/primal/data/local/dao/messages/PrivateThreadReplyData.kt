package net.primal.data.local.dao.messages

import androidx.room3.Entity
import androidx.room3.Index
import net.primal.shared.data.local.encryption.Encryptable

/**
 * A locally decrypted NIP-17 reply. Its content and thread links stay behind the same at-rest
 * encryption boundary as direct-message plaintext and never enter the public PostData cache.
 */
@Entity(
    primaryKeys = ["ownerId", "eventId"],
    indices = [Index(value = ["ownerId", "createdAt"])],
)
data class PrivateThreadReplyData(
    val ownerId: String,
    val eventId: String,
    val outerEventId: String,
    val senderId: String,
    val recipientId: String,
    val createdAt: Long,
    val content: Encryptable<String>,
    val rootId: Encryptable<String>,
    val parentId: Encryptable<String>,
)
