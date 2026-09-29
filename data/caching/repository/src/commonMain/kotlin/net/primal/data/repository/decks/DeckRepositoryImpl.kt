package net.primal.data.repository.decks

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.data.local.dao.decks.DeckColumnData
import net.primal.data.local.dao.decks.DeckData
import net.primal.data.local.db.DeckDatabase
import net.primal.data.repository.mappers.local.asDeckColumnDO
import net.primal.data.repository.mappers.local.asDeckDO
import net.primal.domain.decks.Deck
import net.primal.domain.decks.DeckColumn
import net.primal.domain.decks.DeckColumnParams
import net.primal.domain.decks.DeckColumnType
import net.primal.domain.decks.DeckRepository
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.shared.data.local.db.withTransaction

class DeckRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: DeckDatabase,
) : DeckRepository {

    override fun observeDecks(ownerId: String): Flow<List<Deck>> =
        combine(
            database.decks().observeDecksByOwnerId(ownerId),
            database.decks().observeColumnsByOwnerId(ownerId),
        ) { decks, columns ->
            val columnsByDeckId = columns.groupBy { it.deckId }
            decks.map { deck ->
                deck.asDeckDO(columns = columnsByDeckId[deck.id].orEmpty().map { it.asDeckColumnDO() })
            }
        }.distinctUntilChanged()

    override suspend fun ensureDefaultDeck(ownerId: String) =
        withContext(dispatcherProvider.io()) {
            if (database.decks().countDecksByOwnerId(ownerId) > 0) return@withContext

            val deck =
                DeckData(id = Uuid.random().toString(), ownerId = ownerId, name = DEFAULT_DECK_NAME, position = 0)
            val homeFeedSpec = defaultLibreNostrNoteFeeds(ownerId).first().spec
            val column = DeckColumnData(
                id = Uuid.random().toString(),
                deckId = deck.id,
                type = DeckColumnType.Home,
                paramsJson = DeckColumnParams(feedSpec = homeFeedSpec).encodeToJsonString(),
                position = 0,
            )
            database.withTransaction {
                database.decks().upsertDeck(deck)
                database.decks().upsertColumn(column)
            }
        }

    override suspend fun createDeck(ownerId: String, name: String): Deck =
        withContext(dispatcherProvider.io()) {
            val position = (database.decks().maxDeckPositionByOwnerId(ownerId) ?: -1) + 1
            val deck = DeckData(id = Uuid.random().toString(), ownerId = ownerId, name = name, position = position)
            database.decks().upsertDeck(deck)
            deck.asDeckDO(columns = emptyList())
        }

    override suspend fun renameDeck(
        ownerId: String,
        deckId: String,
        name: String,
    ) = withContext(dispatcherProvider.io()) {
        database.decks().renameDeck(ownerId = ownerId, deckId = deckId, name = name)
    }

    override suspend fun deleteDeck(ownerId: String, deckId: String) =
        withContext(dispatcherProvider.io()) {
            database.withTransaction {
                database.decks().deleteColumnsByDeckId(deckId)
                database.decks().deleteDeck(ownerId = ownerId, deckId = deckId)
            }
        }

    override suspend fun reorderDecks(ownerId: String, orderedDeckIds: List<String>) =
        withContext(dispatcherProvider.io()) {
            database.withTransaction {
                orderedDeckIds.forEachIndexed { index, deckId ->
                    database.decks().updateDeckPosition(ownerId = ownerId, deckId = deckId, position = index)
                }
            }
        }

    override suspend fun addColumn(
        deckId: String,
        type: DeckColumnType,
        paramsJson: String,
    ): DeckColumn =
        withContext(dispatcherProvider.io()) {
            val position = (database.decks().maxColumnPositionByDeckId(deckId) ?: -1) + 1
            val column = DeckColumnData(
                id = Uuid.random().toString(),
                deckId = deckId,
                type = type,
                paramsJson = paramsJson,
                position = position,
            )
            database.decks().upsertColumn(column)
            column.asDeckColumnDO()
        }

    override suspend fun removeColumn(deckId: String, columnId: String) =
        withContext(dispatcherProvider.io()) {
            database.decks().deleteColumn(deckId = deckId, columnId = columnId)
        }

    override suspend fun reorderColumns(deckId: String, orderedColumnIds: List<String>) =
        withContext(dispatcherProvider.io()) {
            database.withTransaction {
                orderedColumnIds.forEachIndexed { index, columnId ->
                    database.decks().updateColumnPosition(deckId = deckId, columnId = columnId, position = index)
                }
            }
        }

    private companion object {
        const val DEFAULT_DECK_NAME = "Home"
    }
}
