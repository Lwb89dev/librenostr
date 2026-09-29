package net.primal.domain.decks

/** A named, ordered set of columns shown side by side in deck mode (tablet landscape). */
data class Deck(
    val id: String,
    val ownerId: String,
    val name: String,
    val position: Int = 0,
    val columns: List<DeckColumn> = emptyList(),
)
