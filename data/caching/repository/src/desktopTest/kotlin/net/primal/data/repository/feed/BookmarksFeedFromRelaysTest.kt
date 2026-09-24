package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.feeds.buildNotesBookmarksFeedSpec
import net.primal.domain.feeds.isRelayServableNotesFeedSpec
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The bookmarks feed used to fall through to the centralized feed API, which in a relay-only build
 * always throws, so the Bookmarks screen could never show anything. Its scope is not a set of
 * authors but a list of ids the user saved, which a relay resolves directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksFeedFromRelaysTest {

    @Test
    fun `a bookmarks feed is claimed as relay servable`() {
        assertTrue(buildNotesBookmarksFeedSpec(userId = ME).isRelayServableNotesFeedSpec())
    }

    @Test
    fun `saved notes come back newest first whatever order they were saved in`() =
        runTest {
            val querier = FakeQuerier(notes = listOf(note("old", 10), note("new", 30), note("mid", 20)))

            val response = fetcher(querier, localIds = listOf("old", "new", "mid")).fetchBookmarks()

            assertEquals(listOf("new", "mid", "old"), response.notes.map { it.id })
        }

    @Test
    fun `the until cursor cuts the page like every other feed`() =
        runTest {
            val querier = FakeQuerier(notes = listOf(note("a", 10), note("b", 20), note("c", 30)))

            val response = fetcher(querier, localIds = listOf("a", "b", "c")).fetchBookmarks(until = 20)

            assertEquals(listOf("b", "a"), response.notes.map { it.id })
        }

    @Test
    fun `a page holds at most the limit`() =
        runTest {
            val querier = FakeQuerier(notes = listOf(note("a", 10), note("b", 20), note("c", 30)))

            val response = fetcher(querier, localIds = listOf("a", "b", "c")).fetchBookmarks(limit = 2)

            assertEquals(listOf("c", "b"), response.notes.map { it.id })
        }

    @Test
    fun `a bookmark that is not a text note is left out rather than shown empty`() =
        runTest {
            val querier = FakeQuerier(
                notes = listOf(
                    note("text", 10),
                    note("repost", 20, kind = NostrEventKind.ShortTextNoteRepost.value),
                    note("article", 30, kind = NostrEventKind.LongFormContent.value),
                ),
            )

            val response = fetcher(querier, localIds = listOf("text", "repost", "article")).fetchBookmarks()

            assertEquals(listOf("text"), response.notes.map { it.id })
        }

    @Test
    fun `an empty local table falls back to the list published on the relays`() =
        runTest {
            // Entries are appended as they are added, so the newest bookmark is the last tag.
            val querier = FakeQuerier(
                notes = listOf(note("first", 10), note("second", 20)),
                bookmarkList = bookmarkList("first", "second"),
            )

            val response = fetcher(querier, localIds = emptyList()).fetchBookmarks()

            assertEquals(listOf("second", "first"), response.notes.map { it.id })
            assertEquals(1, querier.bookmarkListQueries, "the list is read once, from the relays")
        }

    @Test
    fun `the local table is trusted when it has rows, without asking the relays for the list`() =
        runTest {
            val querier = FakeQuerier(notes = listOf(note("a", 10)), bookmarkList = bookmarkList("b"))

            fetcher(querier, localIds = listOf("a")).fetchBookmarks()

            assertEquals(0, querier.bookmarkListQueries)
        }

    @Test
    fun `no bookmarks anywhere is an empty page that asks for no notes`() =
        runTest {
            val querier = FakeQuerier(notes = listOf(note("a", 10)))

            val response = fetcher(querier, localIds = emptyList()).fetchBookmarks()

            assertTrue(response.notes.isEmpty())
            assertEquals(0, querier.idQueries, "with nothing saved there is nothing to resolve")
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun RelayNotesFeedFetcher.fetchBookmarks(limit: Int = 20, until: Long? = null) =
        fetch(
            userId = ME,
            feedSpec = buildNotesBookmarksFeedSpec(userId = ME),
            includeReplies = false,
            limit = limit,
            until = until,
        )

    private fun TestScope.fetcher(querier: RelayEventQuerier, localIds: List<String>): RelayNotesFeedFetcher {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RelayNotesFeedFetcher(
            querier = querier,
            coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            ),
            localBookmarkedNoteIds = { _, limit -> localIds.take(limit) },
        )
    }

    private class FakeQuerier(
        private val notes: List<NostrEvent>,
        private val bookmarkList: NostrEvent? = null,
    ) : RelayEventQuerier {
        var bookmarkListQueries = 0
        var idQueries = 0

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            return when {
                NostrEventKind.BookmarksList.value in kinds -> {
                    bookmarkListQueries++
                    listOfNotNull(bookmarkList)
                }

                filter.ids != null -> {
                    idQueries++
                    val wanted = filter.ids.orEmpty()
                    notes.filter { it.id in wanted }
                }

                else -> emptyList()
            }
        }
    }

    private fun note(id: String, createdAt: Long, kind: Int = NostrEventKind.ShortTextNote.value) =
        NostrEvent(
            id = id,
            pubKey = AUTHOR,
            createdAt = createdAt,
            kind = kind,
            tags = emptyList(),
            content = "note $id",
            sig = "sig",
        )

    private fun bookmarkList(vararg ids: String) =
        NostrEvent(
            id = "bookmark-list",
            pubKey = ME,
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
        const val ME = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val AUTHOR = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
