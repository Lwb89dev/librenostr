package net.primal.domain.decks

import kotlinx.serialization.Serializable
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.domain.feeds.buildAdvancedSearchNotesFeedSpec
import net.primal.domain.feeds.buildProfileAuthoredNotesFeedSpec

/**
 * Type-specific parameters for a [DeckColumn], (de)serialized to/from [DeckColumn.paramsJson].
 * Which fields matter depends on [DeckColumn.type] — see [DeckColumn.resolveFeedSpec].
 */
@Serializable
data class DeckColumnParams(
    val feedSpec: String? = null,
    val pubkey: String? = null,
    val hashtag: String? = null,
)

/** Null when the column has no usable parameters yet, or is a type not backed by a note/article feed. */
fun DeckColumn.resolveFeedSpec(): String? {
    val params = paramsJson.decodeFromJsonStringOrNull<DeckColumnParams>()
    return when (type) {
        DeckColumnType.Home, DeckColumnType.Reads -> params?.feedSpec
        DeckColumnType.Hashtag -> params?.hashtag?.let { buildAdvancedSearchNotesFeedSpec("#$it") }
        DeckColumnType.Profile -> params?.pubkey?.let { buildProfileAuthoredNotesFeedSpec(it) }
        DeckColumnType.Explore, DeckColumnType.Notifications, DeckColumnType.Messages -> null
    }
}
