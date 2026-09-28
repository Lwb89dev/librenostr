package net.primal.data.repository.db

import androidx.paging.PagingSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.notes.FeedPost
import net.primal.data.local.dao.notes.FeedPostDataCrossRef
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.notes.RepostData
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.dao.wot.WotQualifiedPubkeyData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.queries.ChronologicalFeedWithRepostsQueryBuilder
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Real SQLite, real [ChronologicalFeedWithRepostsQueryBuilder] SQL: the web-of-trust JOIN and its
 * bypass are raw SQL, so the only trustworthy check is running the actual query against a real
 * database rather than reading the string.
 *
 * The scenario throughout: the owner follows [QUALIFIED_AUTHOR] directly, and [OUTSIDE_AUTHOR]'s
 * only note in the feed arrived by repost — the one situation where a note's author was never
 * chosen by the owner at all, which is exactly what web-of-trust filtering exists to catch.
 */
class WotFeedFilteringTest {

    @Test
    fun `with the filter off, a repost from outside the network still shows`() =
        withSeededDatabase { database ->
            val rows = load(database, wotFilterActive = false)
            assertEquals(setOf(QUALIFIED_NOTE_ID, OUTSIDE_NOTE_ID), rows.map { it.data.postId }.toSet())
        }

    @Test
    fun `with the filter on, a repost from outside the network is hidden`() =
        withSeededDatabase { database ->
            val rows = load(database, wotFilterActive = true)
            assertEquals(setOf(QUALIFIED_NOTE_ID), rows.map { it.data.postId }.toSet())
        }

    @Test
    fun `a direct follow is never hidden, filter on or off`() =
        withSeededDatabase { database ->
            for (wotFilterActive in listOf(true, false)) {
                val rows = load(database, wotFilterActive = wotFilterActive)
                assertEquals(true, rows.any { it.data.postId == QUALIFIED_NOTE_ID }, "wotFilterActive=$wotFilterActive")
            }
        }

    private suspend fun load(database: CachingDatabase, wotFilterActive: Boolean): List<FeedPost> {
        val query = ChronologicalFeedWithRepostsQueryBuilder(
            feedSpec = FEED_SPEC,
            userPubkey = USER_ID,
            allowMutedThreads = false,
            wotFilterActive = wotFilterActive,
        ).feedQuery()
        val pagingSource = database.feedPosts().feedQuery(query = query)
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        )
        val page = result as? PagingSource.LoadResult.Page ?: fail("load failed: $result")
        return page.data
    }

    /**
     * Seeds: the owner follows [QUALIFIED_AUTHOR] (a plain authored note, in the feed), and
     * [OUTSIDE_AUTHOR]'s note reaches the feed only via a repost by [QUALIFIED_AUTHOR] — never
     * followed directly, and (deliberately) not entered into `WotQualifiedPubkeyData` either, so it
     * represents a stranger's content riding in on someone the owner does trust.
     */
    private fun withSeededDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_wot_feed_filtering_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.profiles().insertOrUpdateAll(
                    data = listOf(profileData(QUALIFIED_AUTHOR), profileData(OUTSIDE_AUTHOR)),
                )
                database.posts().upsertAll(
                    data = listOf(
                        postData(postId = QUALIFIED_NOTE_ID, authorId = QUALIFIED_AUTHOR),
                        postData(postId = OUTSIDE_NOTE_ID, authorId = OUTSIDE_AUTHOR),
                    ),
                )
                database.reposts().upsertAll(
                    data = listOf(
                        RepostData(
                            repostId = "repost-of-$OUTSIDE_NOTE_ID",
                            authorId = QUALIFIED_AUTHOR,
                            createdAt = 1_700_000_100L,
                            tags = emptyList(),
                            postId = OUTSIDE_NOTE_ID,
                            postAuthorId = OUTSIDE_AUTHOR,
                            sig = "sig",
                        ),
                    ),
                )
                database.feedsConnections().connect(
                    data = listOf(
                        FeedPostDataCrossRef(ownerId = USER_ID, feedSpec = FEED_SPEC, eventId = QUALIFIED_NOTE_ID),
                        FeedPostDataCrossRef(
                            ownerId = USER_ID,
                            feedSpec = FEED_SPEC,
                            eventId = "repost-of-$OUTSIDE_NOTE_ID",
                        ),
                    ),
                )
                database.webOfTrust().insertQualifiedPubkeys(
                    data = listOf(WotQualifiedPubkeyData(ownerId = USER_ID, pubkey = QUALIFIED_AUTHOR)),
                )

                block(database)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private fun postData(postId: String, authorId: String) =
        PostData(
            postId = postId,
            authorId = authorId,
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

    private companion object {
        const val USER_ID = "user-pubkey"
        const val QUALIFIED_AUTHOR = "qualified-author"
        const val OUTSIDE_AUTHOR = "outside-author"
        const val QUALIFIED_NOTE_ID = "note-qualified"
        const val OUTSIDE_NOTE_ID = "note-outside"
        const val FEED_SPEC = "test-feed-spec"

        var counter = 0
    }
}
