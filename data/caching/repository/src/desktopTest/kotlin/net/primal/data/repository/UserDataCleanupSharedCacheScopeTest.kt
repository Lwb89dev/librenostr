package net.primal.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Two accounts logged in on the same device share the coordinator's follow-list cache and the
 * hot event layer — both are one process-wide instance, not one per account. Logging out account A
 * while account B is still logged in must not throw that shared warm state away for B; only
 * logging out the *last* account should reset it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDataCleanupSharedCacheScopeTest {

    @Test
    fun `clearing one account's data with other accounts still logged in keeps the shared follow-list cache`() =
        withDatabase { database ->
            val querier = RecordingQuerier()
            val coordinator = FetchCoordinator(dispatcherProvider = testDispatcherProvider())
            coordinator.fetchFollowList(querier = querier, pubkey = SUBJECT_PUBKEY)

            cleanup(database, coordinator).clearUserData(userId = LOGGED_OUT_USER, clearSharedCaches = false)
            coordinator.fetchFollowList(querier = querier, pubkey = SUBJECT_PUBKEY)

            assertEquals(1, querier.followListQueries, "the cached follow list must still answer without a relay")
        }

    @Test
    fun `clearing the last account's data drops the shared follow-list cache`() =
        withDatabase { database ->
            val querier = RecordingQuerier()
            val coordinator = FetchCoordinator(dispatcherProvider = testDispatcherProvider())
            coordinator.fetchFollowList(querier = querier, pubkey = SUBJECT_PUBKEY)

            cleanup(database, coordinator).clearUserData(userId = LOGGED_OUT_USER, clearSharedCaches = true)
            coordinator.fetchFollowList(querier = querier, pubkey = SUBJECT_PUBKEY)

            assertEquals(2, querier.followListQueries, "with nobody left logged in, the stale cache must go")
        }

    // ------------------------------------------------------------------------------- harness

    private fun cleanup(database: CachingDatabase, coordinator: FetchCoordinator) =
        UserDataCleanupRepositoryImpl(
            database = database,
            invalidationTracker = FeedSpecInvalidationTracker(),
            localEventCache = LocalEventCache(database = database),
            fetchCoordinator = coordinator,
        )

    private class RecordingQuerier : RelayEventQuerier {
        var followListQueries = 0

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            if (filter.kinds?.contains(NostrEventKind.FollowList.value) == true) followListQueries++
            return listOf(
                NostrEvent(
                    id = "follow-list-$followListQueries",
                    pubKey = SUBJECT_PUBKEY,
                    createdAt = 1_700_000_000L,
                    kind = NostrEventKind.FollowList.value,
                    tags = emptyList(),
                    content = "",
                    sig = "sig",
                ),
            )
        }
    }

    private fun testDispatcherProvider(): DispatcherProvider {
        val testDispatcher = UnconfinedTestDispatcher()
        return mockk<DispatcherProvider> {
            every { io() } returns testDispatcher
            every { main() } returns testDispatcher
        }
    }

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_shared_cache_scope_${counter++}.db"
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
        const val LOGGED_OUT_USER = "logged-out-user"
        const val SUBJECT_PUBKEY = "subject-pubkey"
        var counter = 0
    }
}
