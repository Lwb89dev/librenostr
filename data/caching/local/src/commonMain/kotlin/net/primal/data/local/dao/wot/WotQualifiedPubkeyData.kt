package net.primal.data.local.dao.wot

import androidx.room3.Entity
import androidx.room3.Index

/**
 * One pubkey judged to be inside a given owner's web of trust: either followed by the owner
 * directly, or followed by enough of the owner's own follows (see `WebOfTrustRepository` for the
 * exact threshold). The feed query joins against this table to decide what a note's author needs
 * to be in for the note to show up when web-of-trust filtering is active.
 */
@Entity(
    primaryKeys = ["ownerId", "pubkey"],
    indices = [Index(value = ["ownerId"])],
)
data class WotQualifiedPubkeyData(
    val ownerId: String,
    val pubkey: String,
)
