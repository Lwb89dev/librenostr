package net.primal.domain.decks

import kotlinx.coroutines.flow.Flow

/**
 * Deck/column layout is a local, per-device UI preference (like which tabs are pinned), not a
 * Nostr list — nothing here is ever published to or fetched from a relay.
 */
interface DeckRepository {

    fun observeDecks(ownerId: String): Flow<List<Deck>>

    /** Seeds a single deck with one Home column the first time [ownerId] has no decks at all. No-op otherwise. */
    suspend fun ensureDefaultDeck(ownerId: String)

    suspend fun createDeck(ownerId: String, name: String): Deck

    suspend fun renameDeck(
        ownerId: String,
        deckId: String,
        name: String,
    )

    suspend fun deleteDeck(ownerId: String, deckId: String)

    suspend fun reorderDecks(ownerId: String, orderedDeckIds: List<String>)

    suspend fun addColumn(
        deckId: String,
        type: DeckColumnType,
        paramsJson: String = "{}",
    ): DeckColumn

    suspend fun removeColumn(deckId: String, columnId: String)

    suspend fun reorderColumns(deckId: String, orderedColumnIds: List<String>)
}
