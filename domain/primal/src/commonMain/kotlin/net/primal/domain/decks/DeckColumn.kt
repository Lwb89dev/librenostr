package net.primal.domain.decks

/**
 * One column inside a [Deck]. [paramsJson] carries the type-specific parameters (e.g. a
 * `feedSpec` for [DeckColumnType.Home], a hashtag for [DeckColumnType.Hashtag], a pubkey for
 * [DeckColumnType.Profile]) as a small JSON blob rather than a table per column type.
 */
data class DeckColumn(
    val id: String,
    val deckId: String,
    val type: DeckColumnType,
    val paramsJson: String = "{}",
    val position: Int = 0,
)
