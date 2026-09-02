package net.primal.data.repository.fetch

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.articles.paging.RelayArticleFeedFetcher
import net.primal.data.repository.feed.RelayNotesFeedFetcher
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The active user's follow list is a kind 3 that every feed needs and that changes rarely. Before
 * the coordinator each feed asked for it separately — the note feed, the article feed, advanced
 * search, explore and the profile screen — and at app start those open together.
 *
 * These tests drive the real fetchers rather than the coordinator on its own, because the thing
 * worth pinning is the wiring: a coordinator nobody is plugged into changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedFollowListTest {

    @Test
    fun `the note feed and the article feed share one follow list request`() =
        runTest {
            val querier = RecordingQuerier()
            val coordinator = coordinator()

            RelayNotesFeedFetcher(querier = querier, coordinator = coordinator)
                .fetch(userId = USER, feedSpec = FEED_SPEC, includeReplies = false, limit = 10)
            RelayArticleFeedFetcher(querier = querier, coordinator = coordinator)
                .fetch(userId = USER, feedSpec = FEED_SPEC, limit = 10, until = null)

            assertEquals(
                1,
                querier.followListQueryCount,
                "the second feed must reuse the list, not ask the relays again",
            )
        }

    @Test
    fun `separate coordinators ask twice`() =
        runTest {
            // The behaviour the shared instance replaces, kept as an explicit negative so a future
            // refactor that quietly gives each fetcher its own coordinator has something to trip on.
            val querier = RecordingQuerier()

            RelayNotesFeedFetcher(querier = querier, coordinator = coordinator())
                .fetch(userId = USER, feedSpec = FEED_SPEC, includeReplies = false, limit = 10)
            RelayArticleFeedFetcher(querier = querier, coordinator = coordinator())
                .fetch(userId = USER, feedSpec = FEED_SPEC, limit = 10, until = null)

            assertEquals(2, querier.followListQueryCount)
        }

    @Test
    fun `a follow list that expires is asked for again`() =
        runTest {
            // Sharing must not turn into never refreshing: following somebody has to reach the
            // feed without restarting the app.
            val querier = RecordingQuerier()
            var now = 1_000L
            val coordinator = coordinator(nowSeconds = { now })

            val fetcher = RelayNotesFeedFetcher(querier = querier, coordinator = coordinator)
            fetcher.fetch(userId = USER, feedSpec = FEED_SPEC, includeReplies = false, limit = 10)
            now += FetchCoordinator.FOLLOW_LIST_TTL_SECONDS + 1
            fetcher.fetch(userId = USER, feedSpec = FEED_SPEC, includeReplies = false, limit = 10)

            assertEquals(2, querier.followListQueryCount)
        }

    // ------------------------------------------------------------------------------- harness

    /** Answers a follow list for [USER] and nothing else, counting what it was asked for. */
    private class RecordingQuerier : RelayEventQuerier {
        var followListQueryCount = 0
            private set

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            if (!kinds.contains(NostrEventKind.FollowList.value)) return emptyList()
            if (filter.authors?.contains(USER) != true) return emptyList()

            followListQueryCount += 1
            return listOf(followListEvent())
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

    private fun kotlinx.coroutines.test.TestScope.coordinator(nowSeconds: () -> Long = { 1_000L }): FetchCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return FetchCoordinator(
            dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
                every { main() } returns dispatcher
            },
            nowSeconds = nowSeconds,
        )
    }

    private companion object {
        const val USER = "user-pubkey"
        const val FOLLOWED = "followed-pubkey"
        const val FEED_SPEC = """{"id":"latest","kind":"notes"}"""
    }
}
