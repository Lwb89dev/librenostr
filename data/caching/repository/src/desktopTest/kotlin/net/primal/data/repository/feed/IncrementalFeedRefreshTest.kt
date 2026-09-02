package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The live subscription reports that something new exists, and the feed then asks the relays what.
 * That refresh used to request a whole page across the entire follow list on every burst, most of
 * it notes the database already held.
 *
 * What has to stay true once it asks only for what is newer: the boundary is inclusive. Nostr
 * timestamps are whole seconds, notes published inside the same second are ordinary, and asking
 * for strictly-newer would drop one silently — the kind of bug that shows up as "a note never
 * appeared" and is never traced back here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalFeedRefreshTest {

    @Test
    fun `the since boundary reaches the relays exactly as given`() =
        runTest {
            val querier = RecordingQuerier()

            fetcher(querier).fetch(
                userId = USER,
                feedSpec = FEED_SPEC,
                includeReplies = false,
                limit = 20,
                since = NEWEST_LOCAL,
            )

            assertEquals(
                listOf(NEWEST_LOCAL),
                querier.noteFilters.map { it.since }.distinct(),
                "a nudged boundary would drop notes sharing a second with the newest one held",
            )
        }

    @Test
    fun `a full refresh still asks without a boundary`() =
        runTest {
            // Opening a feed with nothing stored has no boundary to ask from, and must not
            // accidentally inherit one.
            val querier = RecordingQuerier()

            fetcher(querier).fetch(
                userId = USER,
                feedSpec = FEED_SPEC,
                includeReplies = false,
                limit = 20,
                since = null,
            )

            assertNull(querier.noteFilters.first().since)
        }

    // ------------------------------------------------------------------------------- harness

    /** Answers a one-entry follow list and records the note queries that follow it. */
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
                pubKey = USER,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.FollowList.value,
                tags = listOf(
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(FOLLOWED))
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

    private companion object {
        const val USER = "user-pubkey"
        const val FOLLOWED = "followed-pubkey"
        const val NEWEST_LOCAL = 1_756_684_800L
        const val FEED_SPEC = """{"id":"latest","kind":"notes"}"""
    }
}
