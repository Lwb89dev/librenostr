package net.primal.data.repository.db

import androidx.paging.PagingSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.notes.FeedPost
import net.primal.data.local.dao.notes.FeedPostDataCrossRef
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.queries.ChronologicalFeedWithRepostsQueryBuilder
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.feed.processors.FeedProcessor
import net.primal.domain.nostr.NostrEvent
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * A relay snapshot that came back empty is indistinguishable from "every relay timed out":
 * `RelaysSocketManager.queryEvents` returns an empty result on timeout rather than throwing.
 * Honouring `clearFeed` for such a snapshot wiped the cached page and left the user staring at
 * a blank feed with nothing local left to fall back on, which is what the auto-update on every
 * ON_START triggered whenever the device was offline.
 */
class FeedProcessorEmptySnapshotTest {

    @Test
    fun emptyResponse_withClearFeed_keepsTheCachedFeed() =
        withSeededFeed { database, tracker ->
            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(
                    userId = USER_ID,
                    response = emptyFeedResponse(),
                    clearFeed = true,
                )

            assertEquals(
                expected = listOf(NOTE_ID),
                actual = database.loadFeedNoteIds(),
                message = "an empty relay snapshot must not clear the cached feed",
            )
        }

    @Test
    fun nonEmptyResponse_withClearFeed_stillReplacesTheFeed() =
        withSeededFeed { database, tracker ->
            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(
                    userId = USER_ID,
                    response = feedResponse(noteId = REPLACEMENT_NOTE_ID),
                    clearFeed = true,
                )

            assertEquals(
                expected = listOf(REPLACEMENT_NOTE_ID),
                actual = database.loadFeedNoteIds(),
                message = "a snapshot with events must still replace the cached feed",
            )
        }

    // ---------------------------------------------------------------------------------------------
    // harness
    // ---------------------------------------------------------------------------------------------

    private fun withSeededFeed(block: suspend (CachingDatabase, FeedSpecInvalidationTracker) -> Unit) =
        runBlocking {
            val databaseName = "primal_feed_empty_snapshot_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.profiles().insertOrUpdateAll(data = listOf(profileData(ownerId = AUTHOR_ID)))
                database.posts().upsertAll(data = listOf(postData(postId = NOTE_ID)))
                database.feedsConnections().connect(
                    data = listOf(
                        FeedPostDataCrossRef(ownerId = USER_ID, feedSpec = MAIN_SPEC, eventId = NOTE_ID),
                    ),
                )
                block(database, FeedSpecInvalidationTracker())
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private suspend fun CachingDatabase.loadFeedNoteIds(): List<String> {
        val pagingSource = feedPosts().feedQuery(
            query = ChronologicalFeedWithRepostsQueryBuilder(
                feedSpec = MAIN_SPEC,
                userPubkey = USER_ID,
                allowMutedThreads = false,
            ).feedQuery(),
        )
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        )
        val page = result as? PagingSource.LoadResult.Page<Int, FeedPost> ?: fail("feed load failed: $result")
        return page.data.map { it.data.postId }
    }

    private fun emptyFeedResponse() = feedResponse(noteId = null)

    private fun feedResponse(noteId: String?) =
        FeedResponse(
            paging = null,
            metadata = emptyList(),
            notes = listOfNotNull(
                noteId?.let {
                    NostrEvent(
                        id = it,
                        pubKey = AUTHOR_ID,
                        createdAt = 1_700_000_100L,
                        kind = 1,
                        tags = emptyList(),
                        content = "hello nostr",
                        sig = "signature",
                    )
                },
            ),
            articles = emptyList(),
            reposts = emptyList(),
            zaps = emptyList(),
            referencedEvents = emptyList(),
            primalEventStats = emptyList(),
            primalEventUserStats = emptyList(),
            cdnResources = emptyList(),
            primalLinkPreviews = emptyList(),
            primalRelayHints = emptyList(),
            blossomServers = emptyList(),
        )

    private fun postData(postId: String) =
        PostData(
            postId = postId,
            authorId = AUTHOR_ID,
            createdAt = 1_700_000_000L,
            tags = emptyList(),
            content = "hello nostr",
            uris = emptyList(),
            hashtags = emptyList(),
            sig = "sig",
            raw = "{}",
        )

    private fun profileData(ownerId: String) =
        ProfileData(
            ownerId = ownerId,
            eventId = "metadata-$ownerId",
            createdAt = 1_700_000_000L,
            raw = "{}",
        )

    companion object {
        private const val USER_ID = "user-pubkey"
        private const val AUTHOR_ID = "author-pubkey"
        private const val NOTE_ID = "note-1"
        private const val REPLACEMENT_NOTE_ID = "note-2"
        private const val MAIN_SPEC = """{"id":"latest","kind":"notes"}"""

        private var counter = 0
    }
}
