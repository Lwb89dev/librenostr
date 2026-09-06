package net.primal.data.repository.db

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.queries.ChronologicalFeedWithRepostsQueryBuilder
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.feed.processors.FeedProcessor
import net.primal.domain.nostr.NostrEvent
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Loading more notes while scrolling (APPEND) must not invalidate the feed's PagingSource —
 * doing so used to make even a plain page-2 load visibly flash or jump, since invalidation tears
 * down the source and (with placeholders enabled) reloads a page around the anchor. Only a real
 * refresh, which actually clears and replaces the feed, needs the source recreated.
 */
class FeedProcessorInvalidationTest {

    @Test
    fun append_doesNotInvalidateThePagingSource() =
        withDatabase { database, tracker ->
            val pagingSource = tracker.track(
                ownerId = USER_ID,
                feedSpec = MAIN_SPEC,
                pagingSource = database.feedPosts().feedQuery(
                    query = ChronologicalFeedWithRepostsQueryBuilder(
                        feedSpec = MAIN_SPEC,
                        userPubkey = USER_ID,
                        allowMutedThreads = false,
                    ).feedQuery(),
                ),
            )

            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(userId = USER_ID, response = feedResponse(noteId = "note-2"), clearFeed = false)

            assertFalse(pagingSource.invalid, "an appended page must not invalidate the existing PagingSource")
        }

    @Test
    fun refresh_stillInvalidatesThePagingSource() =
        withDatabase { database, tracker ->
            val pagingSource = tracker.track(
                ownerId = USER_ID,
                feedSpec = MAIN_SPEC,
                pagingSource = database.feedPosts().feedQuery(
                    query = ChronologicalFeedWithRepostsQueryBuilder(
                        feedSpec = MAIN_SPEC,
                        userPubkey = USER_ID,
                        allowMutedThreads = false,
                    ).feedQuery(),
                ),
            )

            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(userId = USER_ID, response = feedResponse(noteId = "note-2"), clearFeed = true)

            assertTrue(pagingSource.invalid, "a real refresh must still invalidate the PagingSource")
        }

    // ---------------------------------------------------------------------------------------------
    // harness
    // ---------------------------------------------------------------------------------------------

    private fun withDatabase(block: suspend (CachingDatabase, FeedSpecInvalidationTracker) -> Unit) =
        runBlocking {
            val databaseName = "primal_feed_invalidation_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.profiles().insertOrUpdateAll(data = listOf(profileData(ownerId = AUTHOR_ID)))
                block(database, FeedSpecInvalidationTracker())
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private fun feedResponse(noteId: String) =
        FeedResponse(
            paging = null,
            metadata = emptyList(),
            notes = listOf(
                NostrEvent(
                    id = noteId,
                    pubKey = AUTHOR_ID,
                    createdAt = 1_700_000_100L,
                    kind = 1,
                    tags = emptyList(),
                    content = "hello nostr",
                    sig = "signature",
                ),
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
        private const val MAIN_SPEC = """{"id":"latest","kind":"notes"}"""

        private var counter = 0
    }
}
