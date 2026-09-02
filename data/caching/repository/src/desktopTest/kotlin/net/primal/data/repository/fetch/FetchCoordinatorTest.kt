package net.primal.data.repository.fetch

import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The coordinator's whole job is deciding whether a request has to be sent, so every test here is
 * about how many reached the relays and who was still waiting when the answer came back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FetchCoordinatorTest {

    @Test
    fun `concurrent requests for the same thing become one relay query`() =
        runTest {
            val querier = GatedQuerier()
            val coordinator = coordinator()

            val first = async { coordinator.fetchFollowList(querier, USER) }
            val second = async { coordinator.fetchFollowList(querier, USER) }
            runCurrent()
            querier.answerWith(listOf(followListEvent()))

            assertEquals(listOf("f1"), first.await().map { it.id })
            assertEquals(listOf("f1"), second.await().map { it.id }, "both callers get the same answer")
            assertEquals(1, querier.queryCount, "the second caller must attach, not ask again")
            assertEquals(1, coordinator.stats().coalescedRequests)
        }

    @Test
    fun `a request that has already finished does not answer the next one`() =
        runTest {
            // Coalescing is not caching. A caller arriving after the answer landed must get a
            // fresh request, otherwise the registry would quietly become a cache with no expiry.
            val querier = GatedQuerier()
            val coordinator = coordinator()

            querier.answerWith(listOf(noteEvent()))
            coordinator.fetch(querier, FetchKey.Custom("thing"), anyFilter())
            coordinator.fetch(querier, FetchKey.Custom("thing"), anyFilter())

            assertEquals(2, querier.queryCount)
            assertEquals(0, coordinator.stats().coalescedRequests)
        }

    @Test
    fun `overlapping metadata requests ask for each author once`() =
        runTest {
            // The people in your feed are the people who reply to you, so two screens asking for
            // overlapping author sets is the common case, not the corner one.
            val querier = GatedQuerier()
            val coordinator = coordinator()

            val feed = async { coordinator.fetchMetadata(querier, listOf(ALICE, BOB)) }
            runCurrent()
            val profile = async { coordinator.fetchMetadata(querier, listOf(BOB, CAROL)) }
            runCurrent()
            querier.answerWith(listOf(metadataEvent(ALICE), metadataEvent(BOB), metadataEvent(CAROL)))

            assertEquals(setOf(ALICE, BOB), feed.await().map { it.pubKey }.toSet())
            assertEquals(setOf(BOB, CAROL), profile.await().map { it.pubKey }.toSet())
            assertEquals(1, coordinator.stats().coalescedRequests, "bob was already being asked about")
            assertEquals(
                listOf(setOf(ALICE, BOB), setOf(CAROL)),
                querier.queries.map { it.authors?.toSet() },
                "the second call must only ask for the author nobody had claimed",
            )
        }

    @Test
    fun `overlapping interaction requests ask about each note once`() =
        runTest {
            // A note that turns up in two feeds had its counters fetched once per feed, because
            // the stats fetcher is built per page and knows nothing of the others.
            val querier = GatedQuerier()
            val coordinator = coordinator()

            val homeFeed = async { coordinator.fetchEventInteractions(querier, listOf(NOTE_A, NOTE_B)) }
            runCurrent()
            val profileFeed = async { coordinator.fetchEventInteractions(querier, listOf(NOTE_B, NOTE_C)) }
            runCurrent()
            querier.answerWith(emptyList())
            homeFeed.await()
            profileFeed.await()

            assertEquals(
                listOf(setOf(NOTE_A, NOTE_B), setOf(NOTE_C)),
                querier.queries.mapNotNull { it.eventTags?.toSet() }.distinct(),
                "the second feed must only ask about the note nobody had claimed",
            )
        }

    @Test
    fun `a caller giving up does not cancel the request others are waiting for`() =
        runTest {
            val querier = GatedQuerier()
            val coordinator = coordinator()

            val leaving = async { coordinator.fetchFollowList(querier, USER) }
            val staying = async { coordinator.fetchFollowList(querier, USER) }
            runCurrent()
            leaving.cancel()
            runCurrent()
            querier.answerWith(listOf(followListEvent()))

            assertEquals(listOf("f1"), staying.await().map { it.id })
            assertEquals(0, querier.cancelledCount, "the shared request still had a reader")
        }

    @Test
    fun `the last caller leaving cancels the request`() =
        runTest {
            // Nobody is left to read the answer, so finishing it spends a round trip on nothing.
            val querier = GatedQuerier()
            val coordinator = coordinator()

            val only = async { coordinator.fetchFollowList(querier, USER) }
            runCurrent()
            only.cancel()
            runCurrent()

            assertEquals(1, querier.cancelledCount)
            assertEquals(1, coordinator.stats().abandonedQueries)
        }

    @Test
    fun `the follow list is not asked for again within its time to live`() =
        runTest {
            // The feeds that want this list open seconds apart as their tabs appear, so they never
            // overlap and coalescing alone would not help them.
            val querier = GatedQuerier()
            var now = 1_000L
            val coordinator = coordinator(nowSeconds = { now })

            querier.answerWith(listOf(followListEvent()))
            coordinator.fetchFollowList(querier, USER)
            now += 60
            val second = coordinator.fetchFollowList(querier, USER)

            assertEquals(1, querier.queryCount)
            assertEquals(listOf("f1"), second.map { it.id })
            assertEquals(1, coordinator.stats().servedFromCache)
        }

    @Test
    fun `the follow list is asked for again once its time to live expires`() =
        runTest {
            // Following somebody has to show up in the feed without restarting the app.
            val querier = GatedQuerier()
            var now = 1_000L
            val coordinator = coordinator(nowSeconds = { now })

            querier.answerWith(listOf(followListEvent()))
            coordinator.fetchFollowList(querier, USER)
            now += FetchCoordinator.FOLLOW_LIST_TTL_SECONDS + 1
            coordinator.fetchFollowList(querier, USER)

            assertEquals(2, querier.queryCount)
        }

    @Test
    fun `clearing the session forgets the follow list`() =
        runTest {
            // Switching account must not leave the previous user's follows answering for the new one.
            val querier = GatedQuerier()
            val coordinator = coordinator()

            querier.answerWith(listOf(followListEvent()))
            coordinator.fetchFollowList(querier, USER)
            coordinator.clearSession()
            coordinator.fetchFollowList(querier, USER)

            assertEquals(2, querier.queryCount)
        }

    @Test
    fun `a failed metadata query comes back empty instead of throwing`() =
        runTest {
            // Metadata is decoration: a missing display name must not take a screen down with it.
            val querier = GatedQuerier()
            val coordinator = coordinator()

            querier.failWith(IllegalStateException("relay is down"))
            val result = coordinator.fetchMetadata(querier, listOf(ALICE))

            assertTrue(result.isEmpty())
        }

    // ------------------------------------------------------------------------------- harness

    /** Holds every query open until the test decides what the relays answered. */
    private class GatedQuerier : RelayEventQuerier {
        val queries = mutableListOf<RelayFilter>()
        var cancelledCount = 0
            private set

        val queryCount: Int get() = queries.size

        private val gate = CompletableDeferred<List<NostrEvent>>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            queries += filter
            try {
                return gate.await()
            } catch (error: CancellationException) {
                cancelledCount += 1
                throw error
            }
        }

        fun answerWith(events: List<NostrEvent>) = gate.complete(events).let { }

        fun failWith(error: Throwable) = gate.completeExceptionally(error).let { }
    }

    private fun TestScope.coordinator(nowSeconds: () -> Long = { 1_000L }): FetchCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return FetchCoordinator(
            dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
                every { main() } returns dispatcher
            },
            nowSeconds = nowSeconds,
        )
    }

    private fun anyFilter() = RelayFilter(kinds = listOf(NostrEventKind.ShortTextNote.value), limit = 1)

    private fun followListEvent() = event(id = "f1", pubkey = USER, kind = NostrEventKind.FollowList.value)

    private fun noteEvent() = event(id = "n1", pubkey = USER, kind = NostrEventKind.ShortTextNote.value)

    private fun metadataEvent(pubkey: String) =
        event(id = "meta-$pubkey", pubkey = pubkey, kind = NostrEventKind.Metadata.value)

    private fun event(
        id: String,
        pubkey: String,
        kind: Int,
    ) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = 1_700_000_000L,
        kind = kind,
        tags = emptyList(),
        content = "",
        sig = "sig",
    )

    private companion object {
        const val USER = "user-pubkey"
        const val ALICE = "alice-pubkey"
        const val BOB = "bob-pubkey"
        const val CAROL = "carol-pubkey"
        const val NOTE_A = "note-a"
        const val NOTE_B = "note-b"
        const val NOTE_C = "note-c"
    }
}
