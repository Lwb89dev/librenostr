package net.primal.domain.posts

/** One normalized thread edge, regardless of whether it came from NIP-10, NIP-22 or NIP-17. */
data class ThreadRelation(
    val eventId: String,
    val rootId: String,
    val parentId: String,
)
