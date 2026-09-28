package net.primal.data.local.dao.wot

import androidx.room3.Entity

/**
 * One row per account: whether that account has web-of-trust filtering turned on, and what the
 * last computed network looked like. [computedAtSeconds] doubles as "has a network ever been
 * computed at all": zero means never, so turning the filter on before the first computation
 * finishes does not hide every note in the feed (see `WebOfTrustRepository`).
 */
@Entity(primaryKeys = ["ownerId"])
data class WotNetworkStateData(
    val ownerId: String,
    val filterEnabled: Boolean,
    val computedAtSeconds: Long,
    val firstDegreeCount: Int,
    val qualifiedCount: Int,
)
