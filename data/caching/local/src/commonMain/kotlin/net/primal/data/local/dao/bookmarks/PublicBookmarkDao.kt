package net.primal.data.local.dao.bookmarks

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface PublicBookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmarks(data: List<PublicBookmark>)

    @Query("DELETE FROM PublicBookmark WHERE ownerId = :userId")
    suspend fun deleteAllBookmarks(userId: String)

    @Query("DELETE FROM PublicBookmark WHERE tagValue = :tagValue")
    suspend fun deleteByTagValue(tagValue: String)

    @Query("SELECT * FROM PublicBookmark WHERE tagValue = :tagValue")
    suspend fun findByTagValue(tagValue: String): PublicBookmark?

    @Query("SELECT * FROM PublicBookmark WHERE ownerId = :userId")
    suspend fun findAll(userId: String): List<PublicBookmark>

    /**
     * The ids of the notes a user bookmarked, most recently bookmarked first.
     *
     * A bookmarks list is rewritten whole, in list order, every time it changes, and NIP-51 clients
     * append new entries at the end. Rows therefore sit in the table in list order, so the rowid
     * descending is the recency order the list itself carries but the table has no column for.
     */
    @Query(
        "SELECT tagValue FROM PublicBookmark " +
            "WHERE ownerId = :userId AND tagType = 'e' ORDER BY rowid DESC LIMIT :limit",
    )
    suspend fun findBookmarkedNoteIds(userId: String, limit: Int): List<String>
}
