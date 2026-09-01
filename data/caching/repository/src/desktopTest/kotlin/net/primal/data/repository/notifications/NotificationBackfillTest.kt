package net.primal.data.repository.notifications

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.notifications.NotificationsApi
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The session-start backfill walks backwards with `until`. Two things can go wrong and neither
 * shows up as a crash: the walk repeats the same page forever because `until` was not moved past
 * the oldest row, or it stops after the first page because a short page was not recognised as the
 * end. Both are pinned here.
 */
class NotificationBackfillTest {

    @Test
    fun `walks back through pages and never asks for the same window twice`() =
        withRepository { repository, querier ->
            repository.syncNotifications(userId = USER_ID, backfillPages = 3)

            val untils = querier.reactionFilters.map { it.until }
            // 200 events per page, descending from 1000: the windows are 1000..801, 800..601 and
            // so on, so each request starts strictly before the previous page's oldest row.
            assertEquals(listOf(null, 800L, 600L, 400L), untils, "each page must start before the last")
            assertTrue(untils.distinct().size == untils.size, "a repeated window means the walk is stuck")
        }

    @Test
    fun `stops early when a page comes back short`() =
        withRepository(eventsPerPage = 5) { repository, querier ->
            // A page shorter than the requested size means the relays have nothing older.
            repository.syncNotifications(userId = USER_ID, backfillPages = 3)

            assertEquals(1, querier.reactionFilters.size, "a short page is the end of the walk")
        }

    @Test
    fun `stops when a page is empty`() =
        withRepository(eventsPerPage = 0) { repository, querier ->
            repository.syncNotifications(userId = USER_ID, backfillPages = 3)

            assertEquals(1, querier.reactionFilters.size)
        }

    // ------------------------------------------------------------------------------- harness

    /**
     * Returns [eventsPerPage] reactions per call, each older than the last, and records the
     * filters it was asked for.
     */
    private class RecordingQuerier(private val eventsPerPage: Int) : RelayEventQuerier {
        val reactionFilters = mutableListOf<RelayFilter>()
        private var nextTimestamp = 1_000L

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            if (!kinds.contains(NostrEventKind.Reaction.value)) return emptyList()

            reactionFilters += filter
            return (0 until eventsPerPage).map {
                val createdAt = nextTimestamp--
                NostrEvent(
                    id = "event-$createdAt",
                    pubKey = ACTOR_ID,
                    createdAt = createdAt,
                    kind = NostrEventKind.Reaction.value,
                    tags = listOf(
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("e"))
                            add(kotlinx.serialization.json.JsonPrimitive("target-note"))
                        },
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("p"))
                            add(kotlinx.serialization.json.JsonPrimitive(USER_ID))
                        },
                    ),
                    content = "+",
                    sig = "sig",
                )
            }
        }
    }

    private fun withRepository(
        eventsPerPage: Int = 200,
        block: suspend (NotificationRepositoryImpl, RecordingQuerier) -> Unit,
    ) = runBlocking {
        val databaseName = "primal_notification_backfill_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        val querier = RecordingQuerier(eventsPerPage)
        try {
            val dispatcher = UnconfinedTestDispatcher()
            val repository = NotificationRepositoryImpl(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
                database = database,
                notificationsApi = mockk<NotificationsApi>(relaxed = true),
                relayEventQuerier = querier,
            )
            block(repository, querier)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
        const val ACTOR_ID = "actor-pubkey"
        var counter = 0
    }
}
