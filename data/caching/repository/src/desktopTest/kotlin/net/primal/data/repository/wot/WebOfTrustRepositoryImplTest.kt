package net.primal.data.repository.wot

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.wot.WotDiscoveryState
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The algorithm is the point of this class, so these tests exercise it end to end against a real
 * database — only the relay side (who follows whom) is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebOfTrustRepositoryImplTest {

    @Test
    fun `direct follows are qualified even with no second-degree data at all`() =
        withRepository { repository, _ ->
            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = setOf(ALICE, BOB))

            val state = repository.observeDiscoveryState(OWNER).first()
            check(state is WotDiscoveryState.Complete) { "expected Complete, got $state" }
            assertEquals(2, state.firstDegreeCount)
            assertEquals(0, state.qualifiedCount)
        }

    @Test
    fun `a stranger followed by fewer than the threshold does not qualify`() =
        withRepository { repository, querier ->
            // 9 first-degree follows all list STRANGER: one short of the threshold of 10.
            val firstDegree = (1..9).map { fakePubkey(100 + it) }.toSet()
            querier.followLists = firstDegree.associateWith { listOf(STRANGER) }

            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = firstDegree)

            assertQualified(repository, expectedQualified = 0)
        }

    @Test
    fun `a stranger followed by exactly the threshold qualifies`() =
        withRepository { repository, querier ->
            val firstDegree = (1..QUALIFICATION_THRESHOLD).map { fakePubkey(200 + it) }.toSet()
            querier.followLists = firstDegree.associateWith { listOf(STRANGER) }

            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = firstDegree)

            assertQualified(repository, expectedQualified = 1)
        }

    @Test
    fun `no follows at all fails instead of computing an empty network`() =
        withRepository { repository, _ ->
            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = emptySet())

            val state = repository.observeDiscoveryState(OWNER).first()
            assertTrue(state is WotDiscoveryState.Failed)
            assertEquals(false, repository.observeFilteringActive(OWNER).first())
        }

    @Test
    fun `only the most recent follow list counts when a relay answers with more than one`() =
        withRepository { repository, querier ->
            // A spec-compliant relay never does this (kind 3 is replaceable), but a stray old
            // revision must not out-vote the current one if some relay ever sends both.
            querier.eventsByAuthor[ALICE] = listOf(
                followListEvent(author = ALICE, follows = listOf(STRANGER), createdAt = 1_000L),
                followListEvent(author = ALICE, follows = emptyList(), createdAt = 2_000L),
            )

            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = setOf(ALICE))

            assertQualified(repository, expectedQualified = 0)
        }

    @Test
    fun `a relay that fails for one chunk does not fail the whole computation`() =
        withRepository { repository, querier ->
            querier.failingAuthors = setOf(ALICE)
            querier.followLists = mapOf(ALICE to listOf(STRANGER), BOB to listOf(STRANGER))

            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = setOf(ALICE, BOB))

            // Only BOB's vote for STRANGER came through; ALICE's chunk failed silently.
            val state = repository.observeDiscoveryState(OWNER).first()
            check(state is WotDiscoveryState.Complete) { "expected Complete, got $state" }
            assertEquals(0, state.qualifiedCount)
        }

    @Test
    fun `refreshing replaces the previous network rather than adding to it`() =
        withRepository { repository, querier ->
            querier.followLists = (1..QUALIFICATION_THRESHOLD).associate { fakePubkey(300 + it) to listOf(STRANGER) }
            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = querier.followLists.keys)
            assertQualified(repository, expectedQualified = 1)

            querier.followLists = emptyMap()
            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = setOf(ALICE))

            assertQualified(repository, expectedQualified = 0)
        }

    @Test
    fun `the filter is only active once enabled AND a network has been computed`() =
        withRepository { repository, _ ->
            assertEquals(false, repository.observeFilteringActive(OWNER).first())

            repository.setFilterEnabled(ownerId = OWNER, enabled = true)
            assertEquals(false, repository.observeFilteringActive(OWNER).first(), "no network computed yet")

            repository.refreshNetwork(ownerId = OWNER, firstDegreeFollows = setOf(ALICE))
            assertEquals(true, repository.observeFilteringActive(OWNER).first())

            repository.setFilterEnabled(ownerId = OWNER, enabled = false)
            assertEquals(false, repository.observeFilteringActive(OWNER).first())
        }

    @Test
    fun `enabling the filter does not by itself compute a network`() =
        withRepository { repository, querier ->
            repository.setFilterEnabled(ownerId = OWNER, enabled = true)

            assertEquals(0, querier.queryCount, "refreshNetwork is the caller's job, not setFilterEnabled's")
        }

    private suspend fun assertQualified(repository: WebOfTrustRepositoryImpl, expectedQualified: Int) {
        val state = repository.observeDiscoveryState(OWNER).first()
        check(state is WotDiscoveryState.Complete) { "expected Complete, got $state" }
        assertEquals(expectedQualified, state.qualifiedCount)
    }

    private fun withRepository(block: suspend TestScope.(WebOfTrustRepositoryImpl, FakeRelayEventQuerier) -> Unit) =
        runTest {
            val databaseName = "primal_wot_repository_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            val querier = FakeRelayEventQuerier()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = WebOfTrustRepositoryImpl(
                dispatcherProvider = mockk<DispatcherProvider> { every { io() } returns dispatcher },
                database = database,
                relayEventQuerier = querier,
                nowSeconds = { 1_700_000_000L },
            )
            try {
                block(repository, querier)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    /** Answers a kind-3 query per author from [followLists] (or [eventsByAuthor] for finer control). */
    private class FakeRelayEventQuerier : RelayEventQuerier {
        var followLists: Map<String, List<String>> = emptyMap()
        val eventsByAuthor: MutableMap<String, List<NostrEvent>> = mutableMapOf()
        var failingAuthors: Set<String> = emptySet()
        var queryCount = 0
            private set

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            queryCount += 1
            val authors = filter.authors.orEmpty()
            if (authors.any { it in failingAuthors }) error("relay unreachable")
            return authors.flatMap { author ->
                eventsByAuthor[author]
                    ?: followLists[author]?.let { follows -> listOf(followListEvent(author, follows)) }
                    ?: emptyList()
            }
        }
    }

    private companion object {
        val OWNER = fakePubkey(1)
        val ALICE = fakePubkey(2)
        val BOB = fakePubkey(3)
        val STRANGER = fakePubkey(4)
        const val QUALIFICATION_THRESHOLD = 10

        var counter = 0

        /** A distinct, well-formed 64-hex-char pubkey per seed — followingPubkeys() rejects anything shorter. */
        fun fakePubkey(seed: Int): String = seed.toString(16).padStart(64, '0')

        fun followListEvent(author: String, follows: List<String>, createdAt: Long = 1_700_000_000L) =
            NostrEvent(
                id = "follow-list-$author-$createdAt",
                pubKey = author,
                createdAt = createdAt,
                kind = NostrEventKind.FollowList.value,
                tags = follows.map { followed ->
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(followed))
                    }
                },
                content = "",
                sig = "sig",
            )
    }
}
