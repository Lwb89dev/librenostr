package net.primal.data.repository.bookmarks

import androidx.paging.PagingSource
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPost
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.queries.ChronologicalFeedWithRepostsQueryBuilder
import net.primal.data.remote.api.users.UsersApi
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Nothing used to read the bookmark list back from the relays, so the bookmark state of a note only
 * ever knew what this device had added itself. The sync that fixes it runs every time the app comes
 * back to the foreground, which is why what it does to the table matters as much as what it reads.
 */
class PublicBookmarksSyncTest {

    @Test
    fun `the relay list is stored with the newest bookmark first`() =
        withRepository(relayList = listOf("first", "second", "third")) { repository, database, _ ->
            repository.fetchAndPersistBookmarks(userId = USER_ID)

            assertEquals(
                listOf("third", "second", "first"),
                database.publicBookmarks().findBookmarkedNoteIds(userId = USER_ID, limit = 10),
            )
        }

    @Test
    fun `a list that has not changed leaves every feed alone`() =
        withRepository(relayList = listOf("first", "second")) { repository, database, trackFeed ->
            repository.fetchAndPersistBookmarks(userId = USER_ID)
            val pagingSource = trackFeed(database)

            repository.fetchAndPersistBookmarks(userId = USER_ID)

            // Rewriting the table invalidates every feed showing a bookmark state, and this runs on
            // every return to the foreground.
            delay(GRACE_MS)
            assertFalse(pagingSource.invalid, "an identical list must not be rewritten")
        }

    @Test
    fun `a changed list replaces the stored one and refreshes the feeds`() =
        withRepository(relayList = listOf("first", "second")) { repository, database, trackFeed ->
            repository.fetchAndPersistBookmarks(userId = USER_ID)
            val pagingSource = trackFeed(database)

            relayList = listOf("first", "second", "third")
            repository.fetchAndPersistBookmarks(userId = USER_ID)

            assertEquals(
                listOf("third", "second", "first"),
                database.publicBookmarks().findBookmarkedNoteIds(userId = USER_ID, limit = 10),
            )
            // The control for the test above: the same source does invalidate when the list changes,
            // so "not invalid" there means the write was skipped and not that nothing was watching.
            val invalidated = withTimeoutOrNull(INVALIDATION_TIMEOUT_MS) {
                while (!pagingSource.invalid) delay(POLL_MS)
                true
            }
            assertTrue(invalidated == true, "a changed list must invalidate the feeds")
        }

    @Test
    fun `an empty answer never wipes a stored list`() =
        withRepository(relayList = listOf("first", "second")) { repository, database, _ ->
            repository.fetchAndPersistBookmarks(userId = USER_ID)

            // A relay that times out answers with nothing, which looks exactly like "no bookmarks".
            relayList = emptyList()
            repository.fetchAndPersistBookmarks(userId = USER_ID)

            assertEquals(
                listOf("second", "first"),
                database.publicBookmarks().findBookmarkedNoteIds(userId = USER_ID, limit = 10),
            )
        }

    @Test
    fun `the limit keeps the most recently bookmarked`() =
        withRepository(relayList = listOf("a", "b", "c")) { repository, database, _ ->
            repository.fetchAndPersistBookmarks(userId = USER_ID)

            assertEquals(
                listOf("c", "b"),
                database.publicBookmarks().findBookmarkedNoteIds(userId = USER_ID, limit = 2),
            )
        }

    // ------------------------------------------------------------------------------- harness

    /** What the fake relays currently publish as the user's kind 10003 list, in list order. */
    private var relayList: List<String> = emptyList()

    private fun withRepository(
        relayList: List<String>,
        block: suspend (
            PublicBookmarksRepositoryImpl,
            CachingDatabase,
            suspend (CachingDatabase) -> PagingSource<Int, FeedPost>,
        ) -> Unit,
    ) = runBlocking {
        this@PublicBookmarksSyncTest.relayList = relayList
        val databaseName = "primal_bookmarks_sync_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        try {
            val repository = PublicBookmarksRepositoryImpl(
                dispatcherProvider = mockk<DispatcherProvider> {
                    val dispatcher = UnconfinedTestDispatcher()
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
                database = database,
                primalPublisher = mockk<PrimalPublisher>(relaxed = true),
                usersApi = mockk<UsersApi>(relaxed = true),
                relayEventQuerier = object : RelayEventQuerier {
                    override suspend fun query(filter: RelayFilter): List<NostrEvent> =
                        listOf(bookmarkListEvent(this@PublicBookmarksSyncTest.relayList))
                },
            )
            block(repository, database) { db -> db.trackFeed() }
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    /** A feed query that has loaded once, which is what makes Room start watching its tables. */
    private suspend fun CachingDatabase.trackFeed(): PagingSource<Int, FeedPost> {
        val pagingSource = feedPosts().feedQuery(
            query = ChronologicalFeedWithRepostsQueryBuilder(
                feedSpec = FEED_SPEC,
                userPubkey = USER_ID,
                allowMutedThreads = false,
            ).feedQuery(),
        )
        pagingSource.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
        assertFalse(pagingSource.invalid, "the feed source must start out valid")
        return pagingSource
    }

    private fun bookmarkListEvent(ids: List<String>) =
        NostrEvent(
            id = "bookmark-list",
            pubKey = USER_ID,
            createdAt = 1_700_000_000L,
            kind = NostrEventKind.BookmarksList.value,
            tags = ids.map { id ->
                buildJsonArray {
                    add(JsonPrimitive("e"))
                    add(JsonPrimitive(id))
                }
            },
            content = "",
            sig = "sig",
        )

    private companion object {
        const val USER_ID = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val FEED_SPEC = """{"id":"latest","kind":"notes"}"""
        const val GRACE_MS = 800L
        const val INVALIDATION_TIMEOUT_MS = 3_000L
        const val POLL_MS = 25L
        var counter = 0
    }
}
