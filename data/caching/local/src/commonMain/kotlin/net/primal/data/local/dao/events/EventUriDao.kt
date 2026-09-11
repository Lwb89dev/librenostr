package net.primal.data.local.dao.events

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.links.EventUriType

@Dao
interface EventUriDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllEventNostrUris(data: List<EventUriNostr>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllEventUris(data: List<EventUri>)

    @Query("SELECT * FROM EventUri WHERE eventId = :noteId AND type IN (:types) ORDER BY position")
    suspend fun loadEventUris(noteId: String, types: List<EventUriType> = EventUriType.entries): List<EventUri>

    /**
     * Every citation still marked "not found", regardless of which note cites it.
     *
     * The classification stored on a row is a one-time snapshot taken when the citing note was
     * first persisted, not a live join — so a citation stays [EventUriNostrType.Unsupported]
     * forever unless something explicitly re-derives it. This is how [reclassifyResolvedNoteCitations]
     * finds the candidates worth re-checking whenever a new batch of posts lands.
     */
    @Query("SELECT * FROM EventUriNostr WHERE type = :type")
    suspend fun findEventUrisByType(type: EventUriNostrType = EventUriNostrType.Unsupported): List<EventUriNostr>

    @Query("SELECT * FROM EventUriNostr WHERE eventId = :eventId ORDER BY position")
    suspend fun findEventNostrUrisByEventId(eventId: String): List<EventUriNostr>
}
