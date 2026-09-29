package net.primal.data.local.dao.bookmarks

import androidx.room3.Entity
import net.primal.domain.bookmarks.BookmarkType

// ownerId is part of the key so two profiles on this device can bookmark the same note without
// one's add/remove overwriting the other's row.
@Entity(primaryKeys = ["tagValue", "ownerId"])
data class PublicBookmark(
    val tagValue: String,
    val tagType: String,
    val bookmarkType: BookmarkType,
    val ownerId: String,
)
