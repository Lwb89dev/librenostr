package net.primal.data.repository.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.feed.RelayThreadFetcher
import net.primal.data.repository.notifications.RelayNotificationsFetcher
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.notifications.NotificationGroup
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Profile metadata is deduplicated per session, and a session means the app, not one object.
 *
 * The cache used to be constructed inside each repository and inside each paging mediator. That
 * reads as session-scoped and is not: a mediator is built per Pager, so every notification tab
 * started empty and asked the relays again for authors the feed had already resolved. These tests
 * pin both halves — sharing the instance removes the second request, and not sharing it brings the
 * duplicate straight back.
 */
class SharedCacheAcrossFetchersTest {

    @Test
    fun `a shared cache asks the relays for an author only once`() =
        withDatabase { database ->
            val querier = RecordingQuerier()
            val cache = LocalEventCache(database)

            RelayThreadFetcher(querier, cache).fetch(noteId = NOTE_ID, kinds = KINDS, limit = 10)
            RelayThreadFetcher(querier, cache).fetch(noteId = NOTE_ID, kinds = KINDS, limit = 10)

            assertEquals(listOf(listOf(AUTHOR)), querier.metadataAuthors, "the second fetch must reuse the claim")
        }

    @Test
    fun `a cache per fetcher asks twice for the same author`() =
        withDatabase { database ->
            // This is the behaviour the shared instance replaces, kept here so a future refactor
            // that quietly reintroduces a per-object cache has something to trip over.
            val querier = RecordingQuerier()

            RelayThreadFetcher(querier, LocalEventCache(database)).fetch(NOTE_ID, KINDS, 10)
            RelayThreadFetcher(querier, LocalEventCache(database)).fetch(NOTE_ID, KINDS, 10)

            assertEquals(listOf(listOf(AUTHOR), listOf(AUTHOR)), querier.metadataAuthors)
        }

    @Test
    fun `an author whose metadata never arrives is asked for again`() =
        withDatabase { database ->
            // A claim kept after an empty answer is how a profile stays a raw npub for the whole
            // session. The second fetch must be allowed to try again.
            val querier = RecordingQuerier(answerMetadata = false)
            val cache = LocalEventCache(database)

            RelayThreadFetcher(querier, cache).fetch(NOTE_ID, KINDS, 10)
            RelayThreadFetcher(querier, cache).fetch(NOTE_ID, KINDS, 10)

            assertEquals(listOf(listOf(AUTHOR), listOf(AUTHOR)), querier.metadataAuthors)
        }

    @Test
    fun `the notification fetcher shares the same claims as the thread fetcher`() =
        withDatabase { database ->
            // The two paths see the same authors constantly — the people in your feed are the
            // people who reply to you. A claim taken by one must count for the other.
            val querier = RecordingQuerier()
            val cache = LocalEventCache(database)

            RelayThreadFetcher(querier, cache).fetch(NOTE_ID, KINDS, 10)
            RelayNotificationsFetcher(querier, cache)
                .fetch(userId = "me", group = NotificationGroup.ALL, limit = 10)

            assertEquals(listOf(listOf(AUTHOR)), querier.metadataAuthors)
        }

    @Test
    fun `the notification fetcher gives back a claim that came back empty`() =
        withDatabase { database ->
            val querier = RecordingQuerier(answerMetadata = false)
            val cache = LocalEventCache(database)

            RelayNotificationsFetcher(querier, cache)
                .fetch(userId = "me", group = NotificationGroup.ALL, limit = 10)
            RelayNotificationsFetcher(querier, cache)
                .fetch(userId = "me", group = NotificationGroup.ALL, limit = 10)

            assertEquals(listOf(listOf(AUTHOR), listOf(AUTHOR)), querier.metadataAuthors)
        }

    // ---------------------------------------------------------------------------- harness

    /** Answers with one note and, unless told otherwise, its author's profile. */
    private class RecordingQuerier(private val answerMetadata: Boolean = true) : RelayEventQuerier {
        val metadataAuthors = mutableListOf<List<String>>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            if (kinds.contains(NostrEventKind.Metadata.value)) {
                metadataAuthors += filter.authors.orEmpty()
                return if (answerMetadata) listOf(metadataEvent()) else emptyList()
            }
            // A reaction on the user's note, so the notification fetcher has an actor to resolve.
            if (filter.pubkeyTags?.contains("me") == true) return listOf(reactionEvent())
            return if (filter.ids?.contains(NOTE_ID) == true) listOf(noteEvent()) else emptyList()
        }

        private fun noteEvent() =
            NostrEvent(
                id = NOTE_ID,
                pubKey = AUTHOR,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.ShortTextNote.value,
                tags = emptyList(),
                content = "hello nostr",
                sig = "sig",
            )

        private fun reactionEvent() =
            NostrEvent(
                id = "reaction-1",
                pubKey = AUTHOR,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.Reaction.value,
                tags = listOf(
                    buildJsonArray {
                        add(JsonPrimitive("e"))
                        add(JsonPrimitive(NOTE_ID))
                    },
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive("me"))
                    },
                ),
                content = "+",
                sig = "sig",
            )

        private fun metadataEvent() =
            NostrEvent(
                id = "metadata-1",
                pubKey = AUTHOR,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.Metadata.value,
                tags = emptyList(),
                content = """{"name":"alice"}""",
                sig = "sig",
            )
    }

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_shared_cache_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                block(database)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private companion object {
        const val NOTE_ID = "note-1"
        const val AUTHOR = "author-pubkey"
        val KINDS = listOf(NostrEventKind.ShortTextNote.value)
        var counter = 0
    }
}
