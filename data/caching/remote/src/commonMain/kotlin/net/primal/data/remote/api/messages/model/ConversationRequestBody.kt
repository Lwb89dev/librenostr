package net.primal.data.remote.api.messages.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.primal.domain.messages.ConversationRelation

@Serializable
data class ConversationRequestBody(
    @SerialName("user_pubkey") val userId: String,
    val relation: ConversationRelation,
    // Only the relay path uses these. Nulls are omitted from the encoded JSON, so the
    // request sent to a non-relay backend is unchanged.
    val limit: Int? = null,
    val until: Long? = null,
)
