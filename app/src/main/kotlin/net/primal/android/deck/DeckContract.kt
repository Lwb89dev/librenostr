package net.primal.android.deck

import net.primal.domain.decks.Deck
import net.primal.domain.decks.DeckColumnParams
import net.primal.domain.decks.DeckColumnType
import net.primal.domain.feeds.PrimalFeed

interface DeckContract {
    data class UiState(
        val decks: List<Deck> = emptyList(),
        val activeDeckId: String? = null,
        val userNoteFeeds: List<PrimalFeed> = emptyList(),
        val userReadsFeeds: List<PrimalFeed> = emptyList(),
    ) {
        val activeDeck: Deck? get() = decks.firstOrNull { it.id == activeDeckId } ?: decks.firstOrNull()
    }

    sealed class UiEvent {
        data class AddColumn(val deckId: String, val type: DeckColumnType, val params: DeckColumnParams) : UiEvent()
        data class RemoveColumn(val deckId: String, val columnId: String) : UiEvent()
        data class MoveColumn(val deckId: String, val columnId: String, val direction: MoveDirection) : UiEvent()
        data class ReorderColumns(val deckId: String, val orderedColumnIds: List<String>) : UiEvent()
        data class CreateDeck(val name: String) : UiEvent()
        data class RenameDeck(val deckId: String, val name: String) : UiEvent()
        data class DeleteDeck(val deckId: String) : UiEvent()
        data class MoveDeck(val deckId: String, val direction: MoveDirection) : UiEvent()
        data class SelectDeck(val deckId: String) : UiEvent()
    }

    enum class MoveDirection { Up, Down }
}
