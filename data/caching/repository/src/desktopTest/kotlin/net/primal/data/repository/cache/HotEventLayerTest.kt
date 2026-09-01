package net.primal.data.repository.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.db.CachingDatabase
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The hot layer answers repeat lookups from memory instead of reading and re-parsing the same
 * rows. Measured on desktop at ~378us per cold lookup of 40 ids against ~15us warm; on Android
 * the database is encrypted, so the read it skips costs more there.
 *
 * What has to stay true is that it is a cache and not a second source of truth: bounded, only
 * ever filled from rows the database really had, and emptied when a user's data is cleared.
 */
class HotEventLayerTest {

    @Test
    fun `a repeated lookup is answered without reading the database again`() =
        withDatabase { database ->
            val cache = LocalEventCache(database)
            database.posts().upsertAll(data = listOf(postData("note-1"), postData("note-2")))
            cache.partitionKnownEventIds(listOf("note-1", "note-2"))

            // Deleting the rows is how the test can tell memory from SQLite: a second lookup that
            // still knows both events cannot have gone back to the database for them.
            database.posts().deletePostById(postId = "note-1")
            database.posts().deletePostById(postId = "note-2")
            val result = cache.partitionKnownEventIds(listOf("note-1", "note-2"))

            assertEquals(setOf("note-1", "note-2"), result.known.map { it.id }.toSet())
            assertTrue(result.missing.isEmpty())
        }

    @Test
    fun `the hot layer is bounded and evicts the least recently used`() =
        withDatabase { database ->
            // Unbounded would be faster and would eventually be an out-of-memory kill.
            val cache = LocalEventCache(database, maxHotEvents = 2)
            database.posts().upsertAll(data = listOf(postData("a"), postData("b"), postData("c")))
            cache.partitionKnownEventIds(listOf("a"))
            cache.partitionKnownEventIds(listOf("b"))
            cache.partitionKnownEventIds(listOf("c"))

            database.posts().deletePostById(postId = "a")
            database.posts().deletePostById(postId = "c")

            // "c" is still hot, "a" was evicted when "c" arrived and is now gone for good.
            assertEquals(listOf("c"), cache.partitionKnownEventIds(listOf("c")).known.map { it.id })
            assertEquals(listOf("a"), cache.partitionKnownEventIds(listOf("a")).missing)
        }

    @Test
    fun `a lookup that touches an event keeps it hot`() =
        withDatabase { database ->
            val cache = LocalEventCache(database, maxHotEvents = 2)
            database.posts().upsertAll(data = listOf(postData("a"), postData("b"), postData("c")))
            cache.partitionKnownEventIds(listOf("a"))
            cache.partitionKnownEventIds(listOf("b"))
            // Touching "a" again must move it out of the way of the next eviction.
            cache.partitionKnownEventIds(listOf("a"))
            cache.partitionKnownEventIds(listOf("c"))

            database.posts().deletePostById(postId = "a")
            database.posts().deletePostById(postId = "b")

            assertEquals(listOf("a"), cache.partitionKnownEventIds(listOf("a")).known.map { it.id })
            assertEquals(listOf("b"), cache.partitionKnownEventIds(listOf("b")).missing, "b was the oldest")
        }

    @Test
    fun `clearing the session empties the hot layer`() =
        withDatabase { database ->
            // A cleared account must not leave its notes answering lookups for the next one.
            val cache = LocalEventCache(database)
            database.posts().upsertAll(data = listOf(postData("note-1")))
            cache.partitionKnownEventIds(listOf("note-1"))
            database.posts().deletePostById(postId = "note-1")

            cache.clearSession()

            assertEquals(listOf("note-1"), cache.partitionKnownEventIds(listOf("note-1")).missing)
        }

    @Test
    fun `a released claim can be made again`() =
        withDatabase { database ->
            // One timed-out or empty kind-0 request must not leave an author rendered as a raw
            // npub for the rest of the session.
            val cache = LocalEventCache(database)
            cache.claimMetadataPubkeys(listOf("alice", "bob"))

            cache.releaseMetadataPubkeys(listOf("alice"))

            assertEquals(listOf("alice"), cache.claimMetadataPubkeys(listOf("alice", "bob")))
        }

    // ---------------------------------------------------------------------------- harness

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_hot_event_layer_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                block(database)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

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
            raw = """{"id":"$postId","pubkey":"$AUTHOR_ID","created_at":1700000000,""" +
                """"kind":1,"tags":[],"content":"hello nostr","sig":"sig"}""",
        )

    private companion object {
        const val AUTHOR_ID = "author-pubkey"
        var counter = 0
    }
}
