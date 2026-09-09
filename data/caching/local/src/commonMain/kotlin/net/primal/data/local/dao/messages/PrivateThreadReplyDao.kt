package net.primal.data.local.dao.messages

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrivateThreadReplyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(data: List<PrivateThreadReplyData>)

    @Query("SELECT * FROM PrivateThreadReplyData WHERE ownerId = :ownerId ORDER BY createdAt ASC")
    fun observeAllByOwnerId(ownerId: String): Flow<List<PrivateThreadReplyData>>

    @Query("DELETE FROM PrivateThreadReplyData WHERE ownerId = :ownerId")
    suspend fun deleteAllByOwnerId(ownerId: String)
}
