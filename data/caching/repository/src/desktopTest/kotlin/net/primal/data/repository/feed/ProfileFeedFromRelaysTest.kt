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
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.feeds.isRelayServableNotesFeedSpec
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Somebody's own notes are a plain `authors` filter, so a profile tab never needed anything a
 * relay cannot do. It asked the centralized feed API anyway, which in a relay-only build always
 * throws, so the notes and replies tabs of every profile read "unable to load content".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileFeedFromRelaysTest {

    @Test
    fun `a profile notes feed is asked of the relays, scoped to that person`() =
        runTest {
            val querier = RecordingQuerier()

            fetcher(querier).fetch(
                userId = ME,
                feedSpec = authoredNotesSpec(SOMEONE),
                includeReplies = false,
                limit = 20,
            )

            assertEquals(
                listOf(listOf(SOMEONE)),
                querier.noteFilters.map { it.authors },
                "a profile feed is one author, not the follow list",
            )
        }

    @Test
    fun `a profile replies feed is asked for the same person`() =
        runTest {
            val querier = RecordingQuerier()

            fetcher(querier).fetch(
                userId = ME,
                feedSpec = authoredRepliesSpec(SOMEONE),
                includeReplies = true,
                limit = 20,
            )

            assertEquals(listOf(listOf(SOMEONE)), querier.noteFilters.map { it.authors })
        }

    @Test
    fun `the follow list is still what a following feed asks for`() =
        runTest {
            // The profile case must not swallow the feed everything else depends on.
            val querier = RecordingQuerier()

            fetcher(querier).fetch(
                userId = ME,
                feedSpec = FOLLOWING_SPEC,
                includeReplies = false,
                limit = 20,
            )

            assertEquals(
                listOf(listOf(FOLLOWED, ME)),
                querier.noteFilters.map { it.authors },
                "a following feed is the follow list plus yourself",
            )
        }

    @Test
    fun `feeds with no relay equivalent are not claimed`() =
        runTest {
            // Media thumbnails and discovery rank things a relay cannot rank. Claiming them would
            // trade an error message for a silently wrong feed, which is worse.
            val mediaSpec = """{"id":"feed","kind":"notes","notes":"user_media_thumbnails","pubkey":"$SOMEONE"}"""

            assertTrue(authoredNotesSpec(SOMEONE).isRelayServableNotesFeedSpec())
            assertTrue(!mediaSpec.isRelayServableNotesFeedSpec())
        }

    // ------------------------------------------------------------------------------- harness

    private class RecordingQuerier : RelayEventQuerier {
        val noteFilters = mutableListOf<RelayFilter>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            if (kinds.contains(NostrEventKind.FollowList.value)) return listOf(followListEvent())
            if (kinds.contains(NostrEventKind.ShortTextNote.value)) noteFilters += filter
            return emptyList()
        }

        private fun followListEvent() =
            NostrEvent(
                id = "follow-list-1",
                pubKey = ME,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.FollowList.value,
                tags = listOf(
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("p"))
                        add(kotlinx.serialization.json.JsonPrimitive(FOLLOWED))
                    },
                ),
                content = "",
                sig = "sig",
            )
    }

    private fun TestScope.fetcher(querier: RelayEventQuerier): RelayNotesFeedFetcher {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RelayNotesFeedFetcher(
            querier = querier,
            coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            ),
        )
    }

    private fun authoredNotesSpec(pubkey: String) =
        """{"id":"feed","kind":"notes","notes":"authored","pubkey":"$pubkey"}"""

    private fun authoredRepliesSpec(pubkey: String) =
        """{"id":"feed","include_replies":true,"kind":"notes","notes":"authored","pubkey":"$pubkey"}"""

    private companion object {
        /** Valid hex pubkeys, because the spec helpers check that before matching. */
        const val ME = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val SOMEONE = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val FOLLOWED = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
        const val FOLLOWING_SPEC = """{"id":"latest","kind":"notes"}"""
    }
}
