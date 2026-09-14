package net.primal.data.local.dao.streams

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamChatMessageDao {

    @Upsert
    suspend fun upsertAll(data: List<StreamChatMessageData>)

    @Upsert
    suspend fun upsert(data: StreamChatMessageData)

    // Capped to the most recent 300 messages: with no LIMIT, every new chat message re-ran and
    // re-emitted the entire history for the stream, growing without bound for as long as a
    // long/popular broadcast was watched. The inner query picks the newest rows; the outer
    // ORDER BY restores ascending order for display.
    @Transaction
    @Query(
        """
            SELECT * FROM (
                SELECT * FROM StreamChatMessageData
                WHERE streamATag = :streamATag
                ORDER BY createdAt DESC
                LIMIT 300
            )
            ORDER BY createdAt ASC
        """,
    )
    fun observeMessages(streamATag: String): Flow<List<StreamChatMessage>>

    @Query("DELETE FROM StreamChatMessageData WHERE streamATag = :streamATag")
    suspend fun deleteMessages(streamATag: String)
}
