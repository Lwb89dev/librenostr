package net.primal.data.repository.notifications.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.RemoteMediator
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.notifications.NotificationsApi
import net.primal.data.repository.cache.LocalEventCache
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Pins the fix for a refresh that used to run unconditionally on every open of the notifications
 * tab, regardless of how much history was already cached — a real cost on a screen users check
 * often. The one thing worth actually guarding against, a cache with just its single newest row
 * (the "apparently non-scrollable one-item list" this used to protect against), still forces a
 * refresh; a well-populated cache does not.
 */
@ExperimentalPagingApi
class NotificationsRemoteMediatorInitializeTest {

    @Test
    fun `a sparsely cached group still forces a refresh`() =
        withMediator(cachedNotifications = 1) { mediator ->
            assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        }

    @Test
    fun `a well populated group skips the refresh`() =
        withMediator(cachedNotifications = 20) { mediator ->
            assertEquals(RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH, mediator.initialize())
        }

    @Test
    fun `an empty group forces a refresh`() =
        withMediator(cachedNotifications = 0) { mediator ->
            assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        }

    // ------------------------------------------------------------------------------- harness

    private fun withMediator(
        cachedNotifications: Int,
        block: suspend (NotificationsRemoteMediator) -> Unit,
    ) = runBlocking {
        val databaseName = "primal_notifications_initialize_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        try {
            repeat(cachedNotifications) { index ->
                val notificationId = "notification-$index"
                database.notifications().upsertAll(
                    data = listOf(
                        NotificationData(
                            notificationId = notificationId,
                            ownerId = USER_ID,
                            createdAt = 1_700_000_000L + index,
                            type = NotificationType.YOUR_POST_WAS_LIKED,
                            actionUserId = "actor-$index",
                            actionPostId = "post-$index",
                        ),
                    ),
                )
                database.notificationGroupCrossRef().insertAll(
                    refs = listOf(
                        NotificationGroupCrossRef(
                            notificationId = notificationId,
                            ownerId = USER_ID,
                            groupKey = NotificationGroup.ALL.name,
                        ),
                    ),
                )
            }

            val dispatcher = UnconfinedTestDispatcher()
            val mediator = NotificationsRemoteMediator(
                userId = USER_ID,
                group = NotificationGroup.ALL,
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
                notificationsApi = mockk<NotificationsApi>(relaxed = true),
                database = database,
                localEventCache = LocalEventCache(database = database),
            )
            block(mediator)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
        var counter = 0
    }
}
