package net.primal.data.repository.feed.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPost
import net.primal.data.local.dao.notes.FeedPostDataCrossRef
import net.primal.data.local.dao.notes.FeedPostRemoteKey
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.feed.FeedApi
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.remote.api.feed.model.MultiKindFeedBySpecRequestBody
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.common.ContentPrimalPaging
import net.primal.domain.nostr.NostrEvent
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Pins the fix for a single flaky/empty relay batch permanently ending APPEND pagination.
 * [FeedProcessor][net.primal.data.repository.feed.processors.FeedProcessor] only writes a
 * `FeedPostRemoteKey` for events actually returned, so an empty batch used to leave the next
 * APPEND request identical to the previous one, tripping the dedup guard and setting
 * `endOfPaginationReached = true` forever — after which Paging3 offers no way back short of a
 * fresh PagingSource generation (`LazyPagingItems.retry()` only re-attempts a load in
 * `LoadState.Error`, never a completed `NotLoading` result).
 */
@ExperimentalPagingApi
class NoteFeedRemoteMediatorAppendResilienceTest {

    @Test
    fun `an empty batch steps the next request further back instead of repeating it`() =
        withMediator { mediator, requests ->
            enqueueEmptyResponse(until = SEED_UNTIL)
            enqueueEmptyResponse(until = SEED_UNTIL)

            val first = mediator.load(LoadType.APPEND, emptyAppendState())
            val second = mediator.load(LoadType.APPEND, emptyAppendState())

            assertEquals(2, requests.size, "both calls should have reached the API")
            assertTrue(
                requests[1].until!! < requests[0].until!!,
                "second request's `until` (${requests[1].until}) must be strictly older than " +
                    "the first's (${requests[0].until}), not a repeat of it",
            )
            assertEquals(false, first.endOfPaginationReachedOrFail())
            assertEquals(false, second.endOfPaginationReachedOrFail())
        }

    @Test
    fun `four consecutive empty batches deliberately give up on the fourth`() =
        withMediator { mediator, _ ->
            repeat(4) { enqueueEmptyResponse(until = SEED_UNTIL) }

            val results = (1..4).map { mediator.load(LoadType.APPEND, emptyAppendState()) }

            results.dropLast(1).forEachIndexed { index, result ->
                assertEquals(
                    false,
                    result.endOfPaginationReachedOrFail(),
                    "attempt ${index + 1} of 4 should not have given up yet",
                )
            }
            assertEquals(true, results.last().endOfPaginationReachedOrFail())
        }

    @Test
    fun `a lone empty batch between two successes never triggers a give-up`() =
        withMediator { mediator, _ ->
            enqueueEmptyResponse(until = SEED_UNTIL)
            enqueueNonEmptyResponse(eventId = "older-note-1")
            enqueueEmptyResponse(until = SEED_UNTIL - 20_000)

            val results = (1..3).map { mediator.load(LoadType.APPEND, emptyAppendState()) }

            results.forEach {
                assertEquals(false, it.endOfPaginationReachedOrFail())
            }
        }

    @Test
    fun `a manual retry after giving up extends the cached feed instead of wiping it`() =
        withMediator { mediator, requests ->
            repeat(4) { enqueueEmptyResponse(until = SEED_UNTIL) }
            repeat(4) { mediator.load(LoadType.APPEND, emptyAppendState()) }

            // Paging3 never hands a new generation a direct APPEND — invalidate() always
            // produces a REFRESH, which is exactly what this simulates: retryAppend() runs the
            // registered handler (resetting the give-up counters, arming manualAppendRetryPending)
            // then invalidates, and the next load Paging3 would issue for the new generation is
            // this REFRESH call.
            enqueueNonEmptyResponse(eventId = "retry-older-note")
            invalidationTracker.retryAppend(ownerId = USER_ID, feedSpec = FEED_SPEC)
            val retryResult = mediator.load(LoadType.REFRESH, emptyAppendState())

            assertEquals(
                false,
                retryResult.endOfPaginationReachedOrFail(),
                "the retry's own fetch result should not itself already be exhausted",
            )
            assertEquals(
                SEED_EVENT_ID,
                database.feedsConnections().findFirstBySpec(ownerId = USER_ID, spec = FEED_SPEC)?.eventId,
                "a manual retry must extend the cached feed, not replace it — the row seeded " +
                    "before any APPEND ran must still be the oldest connection afterwards",
            )
            assertTrue(
                requests.last().until!! < SEED_UNTIL,
                "the retry's fetch must continue further back from the exhausted boundary, not " +
                    "request the newest page the way a real top-of-feed refresh would",
            )
        }

    @Test
    fun `a REFRESH after giving up clears the state for the next APPEND`() =
        withMediator { mediator, _ ->
            repeat(4) { enqueueEmptyResponse(until = SEED_UNTIL) }
            repeat(4) { mediator.load(LoadType.APPEND, emptyAppendState()) }

            enqueueNonEmptyResponse(eventId = "fresh-refresh-note")
            mediator.load(LoadType.REFRESH, emptyAppendState())

            enqueueEmptyResponse(until = REFRESH_SEED_UNTIL)
            val postRefreshAppend = mediator.load(LoadType.APPEND, emptyAppendState())

            assertEquals(
                false,
                postRefreshAppend.endOfPaginationReachedOrFail(),
                "a fresh REFRESH must not inherit the prior session's exhausted give-up state",
            )
        }

    // RemoteMediator.MediatorResult.Success is a plain final class with no equals()/hashCode()
    // override (confirmed via decompiling androidx.paging:paging-common-desktop:3.5.0) — comparing
    // two separately-constructed instances with assertEquals always fails on reference identity
    // regardless of their actual field value, so extract the one field that matters instead.
    private fun RemoteMediator.MediatorResult.endOfPaginationReachedOrFail(): Boolean =
        when (this) {
            is RemoteMediator.MediatorResult.Success -> endOfPaginationReached
            else -> fail("expected a Success result, got $this")
        }

    // ------------------------------------------------------------------------------- harness

    private lateinit var feedApi: FeedApi
    private lateinit var capturedRequests: MutableList<MultiKindFeedBySpecRequestBody>
    private lateinit var database: CachingDatabase
    private lateinit var invalidationTracker: FeedSpecInvalidationTracker

    private fun enqueueEmptyResponse(until: Long?) {
        val slot = slot<MultiKindFeedBySpecRequestBody>()
        coEvery { feedApi.getMultiKindFeedBySpec(body = capture(slot)) } answers {
            capturedRequests.add(slot.captured)
            feedResponse(paging = ContentPrimalPaging(orderBy = "created_at", sinceId = until, untilId = until))
        }
    }

    /**
     * The returned note's timestamp is derived from whatever `until` the mediator actually
     * queried (captured dynamically, not a value chosen ahead of time) so the fixture stays
     * internally consistent regardless of the step-back arithmetic that produced that `until` —
     * a real relay would never return an event created after the boundary it was asked for.
     */
    private fun enqueueNonEmptyResponse(eventId: String) {
        val slot = slot<MultiKindFeedBySpecRequestBody>()
        coEvery { feedApi.getMultiKindFeedBySpec(body = capture(slot)) } answers {
            capturedRequests.add(slot.captured)
            val eventCreatedAt = (slot.captured.until ?: SEED_UNTIL) - 1_000
            feedResponse(
                paging = ContentPrimalPaging(orderBy = "created_at", sinceId = eventCreatedAt, untilId = eventCreatedAt),
                notes = listOf(
                    NostrEvent(
                        id = eventId,
                        pubKey = AUTHOR_ID,
                        createdAt = eventCreatedAt,
                        kind = 1,
                        tags = emptyList(),
                        content = "hello nostr",
                        sig = "signature",
                    ),
                ),
            )
        }
    }

    private fun feedResponse(paging: ContentPrimalPaging?, notes: List<NostrEvent> = emptyList()) =
        FeedResponse(
            paging = paging,
            metadata = emptyList(),
            notes = notes,
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

    private fun emptyAppendState() =
        PagingState<Int, FeedPost>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 20),
            leadingPlaceholderCount = 0,
        )

    private fun withMediator(
        block: suspend (NoteFeedRemoteMediator, List<MultiKindFeedBySpecRequestBody>) -> Unit,
    ) = runBlocking {
        val databaseName = "primal_feed_append_resilience_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        try {
            // Seeds "the last thing we know about" so the very first APPEND has a cursor to
            // start from, exactly like a feed that already has some cached content before the
            // user scrolls into new territory.
            database.feedsConnections().connect(
                data = listOf(FeedPostDataCrossRef(ownerId = USER_ID, feedSpec = FEED_SPEC, eventId = SEED_EVENT_ID)),
            )
            database.feedPostsRemoteKeys().upsert(
                data = listOf(
                    FeedPostRemoteKey(
                        ownerId = USER_ID,
                        eventId = SEED_EVENT_ID,
                        directive = FEED_SPEC,
                        sinceId = SEED_UNTIL,
                        untilId = SEED_UNTIL,
                        cachedAt = 1_700_000_000L,
                    ),
                ),
            )

            capturedRequests = mutableListOf()
            feedApi = mockk()
            invalidationTracker = FeedSpecInvalidationTracker()
            val dispatcher = UnconfinedTestDispatcher()
            val mediator = NoteFeedRemoteMediator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
                feedSpec = FEED_SPEC,
                userId = USER_ID,
                feedApi = feedApi,
                database = database,
                invalidationTracker = invalidationTracker,
                fetchCoordinator = FetchCoordinator(dispatcherProvider = mockk { every { io() } returns dispatcher }),
                localEventCache = LocalEventCache(database = database),
            )
            block(mediator, capturedRequests)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
        const val AUTHOR_ID = "author-pubkey"
        const val FEED_SPEC = """{"id":"latest","kind":"notes"}"""
        const val SEED_EVENT_ID = "seed-note"
        const val SEED_UNTIL = 1_700_000_000L
        const val REFRESH_SEED_UNTIL = 1_700_100_000L

        var counter = 0
    }
}
