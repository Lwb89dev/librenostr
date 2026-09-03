package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Advanced search's `scope:myfollows` was the case named when [FetchCoordinator] was introduced —
 * the note feed, article feed and this both ask for the active user's follow list, often within
 * the same burst of tab loads — but this fetcher queried relays directly and was never actually
 * wired to it. This pins that it now shares the coordinator's follow-list cache instead of always
 * paying for its own round trip.
 */
class RelayAdvancedSearchFeedFetcherTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `myfollows scope reuses an already-cached follow list instead of asking again`() =
        runTest {
            val querier = RecordingQuerier()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            )
            coordinator.fetchFollowList(querier = querier, pubkey = USER_ID)

            RelayAdvancedSearchFeedFetcher(querier = querier, coordinator = coordinator).fetch(
                userId = USER_ID,
                feedSpec = """{"id":"advsearch","query":"scope:myfollows"}""",
                fallbackKinds = listOf(1),
                limit = 20,
            )

            assertEquals(1, querier.followListQueries, "a warm cache must not cost a second relay round trip")
        }

    private class RecordingQuerier : RelayEventQuerier {
        var followListQueries = 0

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            if (filter.kinds?.contains(NostrEventKind.FollowList.value) == true) followListQueries++
            return emptyList()
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
    }
}
