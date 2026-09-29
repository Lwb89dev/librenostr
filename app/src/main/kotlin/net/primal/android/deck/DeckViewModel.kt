package net.primal.android.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.primal.android.deck.DeckContract.UiEvent
import net.primal.android.deck.DeckContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.domain.decks.DeckRepository
import net.primal.domain.feeds.FeedsRepository

@HiltViewModel
class DeckViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val deckRepository: DeckRepository,
    private val feedsRepository: FeedsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        seedDefaultDeckIfNeeded()
        observeState()
    }

    fun setEvent(event: UiEvent) {
        when (event) {
            is UiEvent.AddColumn -> addColumn(event)
            is UiEvent.RemoveColumn -> removeColumn(event)
            is UiEvent.MoveColumn -> moveColumn(event)
            is UiEvent.ReorderColumns -> reorderColumns(event)
            is UiEvent.CreateDeck -> createDeck(event)
            is UiEvent.RenameDeck -> renameDeck(event)
            is UiEvent.DeleteDeck -> deleteDeck(event)
            is UiEvent.MoveDeck -> moveDeck(event)
            is UiEvent.SelectDeck -> _state.update { it.copy(activeDeckId = event.deckId) }
        }
    }

    private fun seedDefaultDeckIfNeeded() =
        viewModelScope.launch {
            val ownerId = activeAccountStore.activeUserId.filter { it.isNotBlank() }.first()
            deckRepository.ensureDefaultDeck(ownerId = ownerId)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeState() =
        viewModelScope.launch {
            activeAccountStore.activeUserId
                .filter { it.isNotBlank() }
                .flatMapLatest { ownerId ->
                    combine(
                        deckRepository.observeDecks(ownerId),
                        feedsRepository.observeNotesFeeds(ownerId),
                        feedsRepository.observeReadsFeeds(ownerId),
                    ) { decks, noteFeeds, readsFeeds ->
                        Triple(decks, noteFeeds, readsFeeds)
                    }
                }
                .collect { (decks, noteFeeds, readsFeeds) ->
                    _state.update {
                        it.copy(decks = decks, userNoteFeeds = noteFeeds, userReadsFeeds = readsFeeds)
                    }
                }
        }

    private fun addColumn(event: UiEvent.AddColumn) =
        viewModelScope.launch {
            deckRepository.addColumn(
                deckId = event.deckId,
                type = event.type,
                paramsJson = event.params.encodeToJsonString(),
            )
        }

    private fun removeColumn(event: UiEvent.RemoveColumn) =
        viewModelScope.launch {
            deckRepository.removeColumn(deckId = event.deckId, columnId = event.columnId)
        }

    private fun moveColumn(event: UiEvent.MoveColumn) =
        viewModelScope.launch {
            val deck = _state.value.decks.firstOrNull { it.id == event.deckId } ?: return@launch
            val ids = deck.columns.map { it.id }
            val reordered = ids.moved(event.columnId, event.direction)
            if (reordered != ids) {
                deckRepository.reorderColumns(deckId = event.deckId, orderedColumnIds = reordered)
            }
        }

    private fun reorderColumns(event: UiEvent.ReorderColumns) =
        viewModelScope.launch {
            deckRepository.reorderColumns(deckId = event.deckId, orderedColumnIds = event.orderedColumnIds)
        }

    private fun createDeck(event: UiEvent.CreateDeck) =
        viewModelScope.launch {
            deckRepository.createDeck(ownerId = activeAccountStore.activeUserId(), name = event.name)
        }

    private fun renameDeck(event: UiEvent.RenameDeck) =
        viewModelScope.launch {
            deckRepository.renameDeck(
                ownerId = activeAccountStore.activeUserId(),
                deckId = event.deckId,
                name = event.name,
            )
        }

    private fun deleteDeck(event: UiEvent.DeleteDeck) =
        viewModelScope.launch {
            deckRepository.deleteDeck(ownerId = activeAccountStore.activeUserId(), deckId = event.deckId)
        }

    private fun moveDeck(event: UiEvent.MoveDeck) =
        viewModelScope.launch {
            val ids = _state.value.decks.map { it.id }
            val reordered = ids.moved(event.deckId, event.direction)
            if (reordered != ids) {
                deckRepository.reorderDecks(ownerId = activeAccountStore.activeUserId(), orderedDeckIds = reordered)
            }
        }

    private fun List<String>.moved(id: String, direction: DeckContract.MoveDirection): List<String> {
        val index = indexOf(id)
        val targetIndex = if (direction == DeckContract.MoveDirection.Up) index - 1 else index + 1
        if (index < 0 || targetIndex < 0 || targetIndex >= size) return this
        val reordered = toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(targetIndex, moved)
        return reordered
    }
}
