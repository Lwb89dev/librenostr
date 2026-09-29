package net.primal.data.local.dao.decks

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

// Decks and their columns are one small aggregate; splitting its queries over two DAOs would
// only scatter the position bookkeeping that has to stay consistent between them.
@Suppress("TooManyFunctions")
@Dao
interface DeckDao {

    @Query("SELECT * FROM DeckData WHERE ownerId = :ownerId ORDER BY position ASC")
    fun observeDecksByOwnerId(ownerId: String): Flow<List<DeckData>>

    @Query(
        """
        SELECT * FROM DeckColumnData
        WHERE deckId IN (SELECT id FROM DeckData WHERE ownerId = :ownerId)
        ORDER BY position ASC
        """,
    )
    fun observeColumnsByOwnerId(ownerId: String): Flow<List<DeckColumnData>>

    @Query("SELECT COUNT(*) FROM DeckData WHERE ownerId = :ownerId")
    suspend fun countDecksByOwnerId(ownerId: String): Int

    /** Null when [ownerId] has no decks yet — the next position is then 0. */
    @Query("SELECT MAX(position) FROM DeckData WHERE ownerId = :ownerId")
    suspend fun maxDeckPositionByOwnerId(ownerId: String): Int?

    /** Null when [deckId] has no columns yet — the next position is then 0. */
    @Query("SELECT MAX(position) FROM DeckColumnData WHERE deckId = :deckId")
    suspend fun maxColumnPositionByDeckId(deckId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeck(deck: DeckData)

    @Query("UPDATE DeckData SET name = :name WHERE id = :deckId AND ownerId = :ownerId")
    suspend fun renameDeck(
        ownerId: String,
        deckId: String,
        name: String,
    )

    @Query("DELETE FROM DeckData WHERE id = :deckId AND ownerId = :ownerId")
    suspend fun deleteDeck(ownerId: String, deckId: String)

    @Query("DELETE FROM DeckColumnData WHERE deckId = :deckId")
    suspend fun deleteColumnsByDeckId(deckId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertColumn(column: DeckColumnData)

    @Query("DELETE FROM DeckColumnData WHERE id = :columnId AND deckId = :deckId")
    suspend fun deleteColumn(deckId: String, columnId: String)

    @Query("UPDATE DeckData SET position = :position WHERE id = :deckId AND ownerId = :ownerId")
    suspend fun updateDeckPosition(
        ownerId: String,
        deckId: String,
        position: Int,
    )

    @Query("UPDATE DeckColumnData SET position = :position WHERE id = :columnId AND deckId = :deckId")
    suspend fun updateColumnPosition(
        deckId: String,
        columnId: String,
        position: Int,
    )
}
