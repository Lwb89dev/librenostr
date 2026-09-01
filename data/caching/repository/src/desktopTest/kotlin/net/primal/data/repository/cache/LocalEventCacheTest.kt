package net.primal.data.repository.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.db.CachingDatabase
import net.primal.shared.data.local.db.LocalDatabaseFactory

class LocalEventCacheTest {

    @Test
    fun `stored ids are returned as events, not merely skipped`() =
        withDatabase { database ->
            // The trap this test exists for: a thread builds its conversation cross-refs from the
            // events in the response, so a cached ancestor that is skipped instead of returned
            // leaves the thread rendering with holes — faster and wrong.
            val cache = LocalEventCache(database)
            database.posts().upsertAll(data = listOf(postData("known-1"), postData("known-2")))

            val result = cache.partitionKnownEventIds(listOf("known-1", "known-2", "absent"))

            assertEquals(listOf("absent"), result.missing)
            assertEquals(setOf("known-1", "known-2"), result.known.map { it.id }.toSet())
        }

    @Test
    fun `an empty database asks for everything`() =
        withDatabase { database ->
            val cache = LocalEventCache(database)

            val result = cache.partitionKnownEventIds(listOf("a", "b"))

            assertTrue(result.known.isEmpty())
            assertEquals(listOf("a", "b"), result.missing)
        }

    @Test
    fun `duplicate ids are collapsed`() =
        withDatabase { database ->
            val cache = LocalEventCache(database)

            val result = cache.partitionKnownEventIds(listOf("a", "a", "b"))

            assertEquals(listOf("a", "b"), result.missing)
        }

    @Test
    fun `a post whose raw json cannot be decoded is requested again`() =
        withDatabase { database ->
            // Better to pay a round trip than to hand the caller a hole it cannot detect.
            val cache = LocalEventCache(database)
            database.posts().upsertAll(data = listOf(postData("broken", raw = "not json")))

            val result = cache.partitionKnownEventIds(listOf("broken"))

            assertTrue(result.known.isEmpty())
            assertEquals(listOf("broken"), result.missing)
        }

    @Test
    fun `metadata is claimed once per session`() =
        withDatabase { database ->
            val cache = LocalEventCache(database)

            val first = cache.claimMetadataPubkeys(listOf("alice", "bob"))
            val second = cache.claimMetadataPubkeys(listOf("alice", "bob", "carol"))

            assertEquals(listOf("alice", "bob"), first)
            assertEquals(listOf("carol"), second, "already-claimed pubkeys must not be re-requested")
        }

    @Test
    fun `clearing the session lets metadata be requested again`() =
        withDatabase { database ->
            val cache = LocalEventCache(database)
            cache.claimMetadataPubkeys(listOf("alice"))

            cache.clearSession()

            assertEquals(listOf("alice"), cache.claimMetadataPubkeys(listOf("alice")))
        }

    // ---------------------------------------------------------------------------- harness

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_local_event_cache_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                block(database)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private fun postData(postId: String, raw: String? = null) =
        PostData(
            postId = postId,
            authorId = AUTHOR_ID,
            createdAt = 1_700_000_000L,
            tags = emptyList(),
            content = "hello nostr",
            uris = emptyList(),
            hashtags = emptyList(),
            sig = "sig",
            raw = raw ?: rawEvent(postId),
        )

    private fun rawEvent(id: String) =
        """{"id":"$id","pubkey":"$AUTHOR_ID","created_at":1700000000,""" +
            """"kind":1,"tags":[],"content":"hello nostr","sig":"sig"}"""

    private companion object {
        const val AUTHOR_ID = "author-pubkey"
        var counter = 0
    }
}
