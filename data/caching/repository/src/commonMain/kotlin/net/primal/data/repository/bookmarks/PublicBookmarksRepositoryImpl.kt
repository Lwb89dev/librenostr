package net.primal.data.repository.bookmarks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.createAppBuildHelper
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.bookmarks.PublicBookmark as PublicBookmarkPO
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.users.UsersApi
import net.primal.domain.bookmarks.BookmarkType
import net.primal.domain.bookmarks.PublicBookmarksRepository
import net.primal.domain.bookmarks.TagBookmark
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.PublicBookmarksNotFoundException
import net.primal.domain.nostr.asClientTag
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.withTransaction

class PublicBookmarksRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val primalPublisher: PrimalPublisher,
    private val usersApi: UsersApi,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : PublicBookmarksRepository {

    private val appBuildHelper = createAppBuildHelper()

    private val bookmarksUpdateMutex = Mutex()

    private suspend fun fetchLatestPublicBookmarks(userId: String): Set<TagBookmark>? {
        relayEventQuerier?.let { querier ->
            val relayResult = runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.BookmarksList.value),
                        authors = listOf(userId),
                        limit = BOOKMARK_EVENT_LIMIT,
                    ),
                ).maxByOrNull { it.createdAt }
            }

            // An empty relay result is authoritative: the user has no public
            // bookmarks yet. Only a relay query failure should use the legacy
            // API fallback.
            if (relayResult.isSuccess) {
                return relayResult.getOrNull()?.tags?.parseAsPublicBookmarks() ?: emptySet()
            }
        }

        val bookmarksResponse = withContext(dispatcherProvider.io()) {
            usersApi.getUserBookmarksList(userId = userId)
        }
        return bookmarksResponse.bookmarksListEvent?.tags?.parseAsPublicBookmarks()
    }

    override suspend fun fetchAndPersistBookmarks(userId: String) {
        val bookmarks = fetchLatestPublicBookmarks(userId = userId)
        val rows = bookmarks.asPublicBookmarkRows(userId = userId)

        // This runs every time the app returns to the foreground. Rewriting the table invalidates
        // every feed that shows a bookmark state, so a list that has not changed is left alone.
        val stored = withContext(dispatcherProvider.io()) { database.publicBookmarks().findAll(userId = userId) }
        if (stored.toSet() == rows.toSet()) return

        // A relay query that times out comes back empty rather than failing, and an empty answer
        // is indistinguishable from "the user has no bookmarks". Believing it here would wipe a
        // real local list on a flaky connection, so an empty answer never replaces a stored one.
        // The cost is that clearing the whole list on another device is not mirrored here; removing
        // bookmarks in this app deletes their rows itself.
        if (rows.isEmpty() && stored.isNotEmpty()) return

        persistUserBookmarks(userId = userId, bookmarks = bookmarks)
    }

    private suspend fun persistUserBookmarks(userId: String, bookmarks: Set<TagBookmark>?) {
        withContext(dispatcherProvider.io()) {
            val bookmarksDao = database.publicBookmarks()
            val rows = bookmarks.asPublicBookmarkRows(userId = userId)

            database.withTransaction {
                bookmarksDao.deleteAllBookmarks(userId = userId)
                bookmarksDao.upsertBookmarks(data = rows)
            }
        }
    }

    /**
     * The rows a list is stored as: its notes first, then its long-form articles.
     *
     * The order is the list's own, and it is what [PublicBookmarkPO]'s recency is read back from,
     * so it must survive the trip through the table.
     */
    private fun Set<TagBookmark>?.asPublicBookmarkRows(userId: String): List<PublicBookmarkPO> {
        val notesBookmarks = this?.filter { it.type == "e" }?.map {
            PublicBookmarkPO(
                ownerId = userId,
                bookmarkType = BookmarkType.Note,
                tagType = it.type,
                tagValue = it.value,
            )
        } ?: emptyList()

        val articleBookmarks = this?.filter { it.type == "a" }?.mapNotNull {
            val kind = it.value.split(":").getOrNull(index = 0)?.toIntOrNull()
            if (kind == NostrEventKind.LongFormContent.value) {
                PublicBookmarkPO(
                    ownerId = userId,
                    bookmarkType = BookmarkType.Article,
                    tagType = it.type,
                    tagValue = it.value,
                )
            } else {
                null
            }
        } ?: emptyList()

        return notesBookmarks + articleBookmarks
    }

    override suspend fun isBookmarked(userId: String, tagValue: String) =
        withContext(dispatcherProvider.io()) {
            database.publicBookmarks().findByTagValue(userId = userId, tagValue = tagValue) != null
        }

    override suspend fun addToBookmarks(
        userId: String,
        bookmarkType: BookmarkType,
        tagValue: String,
        forceUpdate: Boolean,
    ) = withContext(dispatcherProvider.io()) {
        val tagType = bookmarkType.toTagType()
        publishAddBookmark(
            userId = userId,
            bookmark = TagBookmark(type = tagType, value = tagValue),
            forceUpdate = forceUpdate,
        )

        database.publicBookmarks().upsertBookmarks(
            data = listOf(
                PublicBookmarkPO(
                    ownerId = userId,
                    bookmarkType = bookmarkType,
                    tagType = tagType,
                    tagValue = tagValue,
                ),
            ),
        )
    }

    override suspend fun removeFromBookmarks(
        userId: String,
        bookmarkType: BookmarkType,
        tagValue: String,
        forceUpdate: Boolean,
    ) = withContext(dispatcherProvider.io()) {
        publishRemoveBookmark(
            userId = userId,
            bookmark = TagBookmark(type = bookmarkType.toTagType(), value = tagValue),
            forceUpdate = forceUpdate,
        )

        database.publicBookmarks().deleteByTagValue(userId = userId, tagValue = tagValue)
    }

    private fun BookmarkType.toTagType() =
        when (this) {
            BookmarkType.Note -> "e"
            BookmarkType.Article, BookmarkType.Stream -> "a"
        }

    private suspend fun publishAddBookmark(
        userId: String,
        forceUpdate: Boolean,
        bookmark: TagBookmark,
    ) {
        publishBookmarksList(userId = userId, forceUpdate = forceUpdate) {
            toMutableSet().apply { add(bookmark) }
        }
    }

    private suspend fun publishRemoveBookmark(
        userId: String,
        forceUpdate: Boolean,
        bookmark: TagBookmark,
    ) {
        publishBookmarksList(userId = userId, forceUpdate = forceUpdate) {
            toMutableSet().apply { remove(bookmark) }
        }
    }

    /**
     * Read-modify-publish of the NIP-51 bookmarks list. See MutedItemRepositoryImpl's
     * updateAndPublishList for the full story; in short, a relay timeout reads as an empty list,
     * so an empty read falls back to the local copy instead of publishing a one-item list over
     * the real one, the latest event's (encrypted, private) content is carried over instead of
     * blanked, and [bookmarksUpdateMutex] keeps two quick taps from racing on the same base.
     */
    private suspend fun publishBookmarksList(
        userId: String,
        forceUpdate: Boolean,
        reducer: Set<TagBookmark>.() -> Set<TagBookmark>,
    ) = bookmarksUpdateMutex.withLock {
        val querier = relayEventQuerier
        val remoteEvent = querier?.let { fetchLatestBookmarksEvent(querier = it, userId = userId) }

        val latestBookmarks: Set<TagBookmark>
        val content: String
        when {
            remoteEvent != null -> {
                latestBookmarks = remoteEvent.tags.parseAsPublicBookmarks()
                content = remoteEvent.content
            }

            querier != null -> {
                latestBookmarks = withContext(dispatcherProvider.io()) {
                    database.publicBookmarks().findAll(userId = userId)
                        .map { TagBookmark(type = it.tagType, value = it.tagValue) }
                        .toSet()
                }
                content = ""
            }

            else -> {
                latestBookmarks = fetchLatestPublicBookmarks(userId = userId)
                    ?: if (forceUpdate) emptySet() else throw PublicBookmarksNotFoundException()
                content = ""
            }
        }

        val updatedBookmarks = latestBookmarks.reducer()

        withContext(dispatcherProvider.io()) {
            val bookmarksTags = updatedBookmarks.map {
                buildJsonArray {
                    add(it.type)
                    add(it.value)
                }
            }
            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.BookmarksList.value,
                    content = content,
                    tags = bookmarksTags + listOf(appBuildHelper.getClientName().asClientTag()),
                ),
            )
        }

        persistUserBookmarks(userId = userId, bookmarks = updatedBookmarks)
    }

    private suspend fun fetchLatestBookmarksEvent(querier: RelayEventQuerier, userId: String): NostrEvent? =
        runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.BookmarksList.value),
                    authors = listOf(userId),
                    limit = BOOKMARK_EVENT_LIMIT,
                ),
            ).maxByOrNull { it.createdAt }
        }.getOrNull()

    private fun List<JsonArray>.parseAsPublicBookmarks(): Set<TagBookmark> {
        return mapNotNull {
            val type = it.getOrNull(0)?.jsonPrimitive?.content
            val value = it.getOrNull(1)?.jsonPrimitive?.content
            // The client tag is re-added fresh on every publish; keeping the old one here would
            // republish it as a "bookmark" next to the new one.
            if (type != null && value != null && type != CLIENT_TAG) {
                TagBookmark(type = type, value = value)
            } else {
                null
            }
        }.toSet()
    }

    private companion object {
        const val BOOKMARK_EVENT_LIMIT = 5
        const val CLIENT_TAG = "client"
    }
}
