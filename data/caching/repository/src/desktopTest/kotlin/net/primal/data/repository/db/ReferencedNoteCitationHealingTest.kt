package net.primal.data.repository.db

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.feed.FeedApi
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.feed.FeedRepositoryImpl
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.feed.processors.FeedProcessor
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Regression cover for a quote that never stops saying "Mentioned event not found", the exact
 * complaint that led here: the retry button on that card really did fetch the missing note, but
 * nothing ever went back to tell the *citing* note's own stored row that its target had arrived —
 * see [EventUriDao.findEventUrisByType][net.primal.data.local.dao.events.EventUriDao.findEventUrisByType]'s
 * doc for why that row is a frozen snapshot rather than a live join.
 *
 * These exercise [net.primal.data.repository.feed.processors.reclassifyResolvedNoteCitations]
 * through the same public entry point every fetcher persists through
 * ([FeedProcessor.processAndPersistToDatabase]), not by calling it directly, so the tests keep
 * covering the real, wired-up behavior rather than an implementation detail.
 */
class ReferencedNoteCitationHealingTest {

    @Test
    fun `a quote heals once its target note and author both arrive later`() =
        withDatabase { database, tracker ->
            val citingContent = "look at this nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}"
            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = CITING_NOTE_ID, author = CITING_AUTHOR_ID, content = citingContent),
                clearFeed = false,
            )

            val beforeHealing = database.eventUris().findEventUrisByType(EventUriNostrType.Unsupported)
            assertEquals(1, beforeHealing.size, "the quote has nothing to resolve it against yet")
            assertEquals(CITING_NOTE_ID, beforeHealing.single().eventId)

            // A second, unrelated response is what any later fetch of the quoted note looks like —
            // opening its thread, seeing it in a feed, or retrying the "not found" card itself.
            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = QUOTED_NOTE_ID, author = QUOTED_AUTHOR_ID, content = "the original note"),
                clearFeed = false,
            )

            val stillUnsupported = database.eventUris().findEventUrisByType(EventUriNostrType.Unsupported)
            assertTrue(stillUnsupported.none { it.eventId == CITING_NOTE_ID }, "the quote must heal")

            val healed = database.eventUris().findEventNostrUrisByEventId(CITING_NOTE_ID).single()
            assertEquals(EventUriNostrType.Note, healed.type)
            val referencedNote = assertNotNull(healed.referencedNote, "the resolved quote needs its preview content")
            assertEquals(QUOTED_NOTE_ID, referencedNote.postId)
        }

    @Test
    fun `healing keeps the citing note's other embeds in their original order`() =
        withDatabase { database, tracker ->
            val citingContent = "first nostr:${ALREADY_KNOWN_NOTE_ID.hexToNoteHrp()} " +
                "then nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}"
            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = notesResponse(
                    nostrEvent(id = CITING_NOTE_ID, author = CITING_AUTHOR_ID, content = citingContent),
                    nostrEvent(id = ALREADY_KNOWN_NOTE_ID, author = QUOTED_AUTHOR_ID, content = "already known"),
                ),
                clearFeed = false,
            )

            val beforeHealing = database.eventUris().findEventNostrUrisByEventId(CITING_NOTE_ID)
            assertEquals(2, beforeHealing.size, "both embeds in the content must produce a row")
            val resolvedRowPosition = beforeHealing.first { it.type == EventUriNostrType.Note }.position
            val unresolvedRowPosition = beforeHealing.first { it.type == EventUriNostrType.Unsupported }.position

            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = QUOTED_NOTE_ID, author = QUOTED_AUTHOR_ID, content = "the original note"),
                clearFeed = false,
            )

            val afterHealing = database.eventUris().findEventNostrUrisByEventId(CITING_NOTE_ID)
            assertTrue(afterHealing.all { it.type == EventUriNostrType.Note }, "both embeds must resolve now")
            assertEquals(
                resolvedRowPosition,
                afterHealing.first { it.uri.contains(ALREADY_KNOWN_NOTE_ID.hexToNoteHrp()) }.position,
                "an embed that was already resolved must not move",
            )
            assertEquals(
                unresolvedRowPosition,
                afterHealing.first { it.uri.contains(QUOTED_NOTE_ID.hexToNoteHrp()) }.position,
                "healing in place must not push the embed to the end of the note's content",
            )
        }

    @Test
    fun `a quote of a note that never arrives stays unresolved`() =
        withDatabase { database, tracker ->
            val citingContent = "look at this nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}"
            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = CITING_NOTE_ID, author = CITING_AUTHOR_ID, content = citingContent),
                clearFeed = false,
            )

            // An unrelated fetch — something that resolves nothing this citing note actually needs.
            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = UNRELATED_NOTE_ID, author = CITING_AUTHOR_ID, content = "unrelated"),
                clearFeed = false,
            )

            val stillUnsupported = database.eventUris().findEventUrisByType(EventUriNostrType.Unsupported)
            assertTrue(
                stillUnsupported.any { it.eventId == CITING_NOTE_ID },
                "a citation must stay unresolved until its actual target shows up",
            )
        }

    @Test
    fun `findResolvedNostrUri reflects the same healing the retry button relies on`() =
        withDatabase { database, tracker ->
            val citingContent = "look at this nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}"
            val uri = "nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}"
            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = CITING_NOTE_ID, author = CITING_AUTHOR_ID, content = citingContent),
                clearFeed = false,
            )

            assertNull(
                feedRepository(database, tracker).findResolvedNostrUri(eventId = CITING_NOTE_ID, uri = uri),
                "must not report a citation as resolved before its target ever arrives",
            )

            processor(database, tracker).processAndPersistToDatabase(
                userId = USER_ID,
                response = noteResponse(id = QUOTED_NOTE_ID, author = QUOTED_AUTHOR_ID, content = "the original note"),
                clearFeed = false,
            )

            val resolved = assertNotNull(
                feedRepository(database, tracker).findResolvedNostrUri(eventId = CITING_NOTE_ID, uri = uri),
                "must report the citation as resolved once its target has been persisted",
            )
            assertEquals(EventUriNostrType.Note, resolved.type)
            assertEquals(QUOTED_NOTE_ID, resolved.referencedNote?.postId)
        }

    // ---------------------------------------------------------------------------------------------
    // harness
    // ---------------------------------------------------------------------------------------------

    private fun feedRepository(database: CachingDatabase, tracker: FeedSpecInvalidationTracker) =
        FeedRepositoryImpl(
            feedApi = mockk<FeedApi>(),
            database = database,
            dispatcherProvider = testDispatcherProvider(),
            invalidationTracker = tracker,
            localEventCache = LocalEventCache(database = database),
            fetchCoordinator = FetchCoordinator(dispatcherProvider = testDispatcherProvider()),
        )

    private fun testDispatcherProvider(): DispatcherProvider {
        val testDispatcher = UnconfinedTestDispatcher()
        return mockk<DispatcherProvider> {
            every { io() } returns testDispatcher
            every { main() } returns testDispatcher
        }
    }

    private fun processor(database: CachingDatabase, tracker: FeedSpecInvalidationTracker) =
        FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)

    private fun withDatabase(block: suspend (CachingDatabase, FeedSpecInvalidationTracker) -> Unit) =
        runBlocking {
            val databaseName = "primal_citation_healing_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.profiles().insertOrUpdateAll(
                    data = listOf(profileData(CITING_AUTHOR_ID), profileData(QUOTED_AUTHOR_ID)),
                )
                block(database, FeedSpecInvalidationTracker())
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private fun noteResponse(id: String, author: String, content: String) =
        notesResponse(nostrEvent(id = id, author = author, content = content))

    private fun notesResponse(vararg events: NostrEvent) =
        FeedResponse(
            paging = null,
            metadata = emptyList(),
            notes = events.toList(),
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

    private fun nostrEvent(id: String, author: String, content: String) =
        NostrEvent(
            id = id,
            pubKey = author,
            createdAt = 1_700_000_100L,
            kind = 1,
            tags = emptyList(),
            content = content,
            sig = "signature",
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
        const val MAIN_SPEC = """{"id":"latest","kind":"notes"}"""

        val CITING_NOTE_ID = "1".repeat(64)
        val CITING_AUTHOR_ID = "a".repeat(64)
        val QUOTED_NOTE_ID = "2".repeat(64)
        val QUOTED_AUTHOR_ID = "b".repeat(64)
        val ALREADY_KNOWN_NOTE_ID = "3".repeat(64)
        val UNRELATED_NOTE_ID = "4".repeat(64)

        var counter = 0
    }
}
