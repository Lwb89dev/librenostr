package net.primal.data.local.dao.wot

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: WotNetworkStateData)

    @Query("SELECT * FROM WotNetworkStateData WHERE ownerId = :ownerId")
    fun observeState(ownerId: String): Flow<WotNetworkStateData?>

    @Query("SELECT * FROM WotNetworkStateData WHERE ownerId = :ownerId")
    suspend fun getState(ownerId: String): WotNetworkStateData?

    @Query("DELETE FROM WotQualifiedPubkeyData WHERE ownerId = :ownerId")
    suspend fun deleteQualifiedPubkeys(ownerId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQualifiedPubkeys(data: List<WotQualifiedPubkeyData>)
}
