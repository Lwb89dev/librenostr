package net.primal.data.repository.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.feed.emptyFeedResponse
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * A notification stored for the first time used to always start unread, whatever its age. History
 * pulled in by scrolling down, or by a refresh filling a gap a relay had left, landed in the unread
 * block that the screen shows above everything else: three-week-old likes on top, today's pushed
 * below them, until "mark all as read" put each back in its chronological place.
 */
class NotificationFirstStoreSeenStateTest {

    @Test
    fun `history fetched after the user last marked everything as seen starts as seen`() =
        withDatabase { database ->
            seed(database, notification(id = "known", createdAt = 1_000, seenAt = LAST_SEEN))

            persist(database, notification(id = "old-history", createdAt = 100))

            assertEquals(LAST_SEEN, stored(database, "old-history").seenGloballyAt)
            assertEquals(emptyList(), unseen(database), "history must not show up as news")
        }

    @Test
    fun `a notification newer than the last mark-all stays unread`() =
        withDatabase { database ->
            seed(database, notification(id = "known", createdAt = 1_000, seenAt = LAST_SEEN))

            persist(database, notification(id = "fresh", createdAt = LAST_SEEN + 60))

            assertNull(stored(database, "fresh").seenGloballyAt)
            assertEquals(listOf("fresh"), unseen(database))
        }

    @Test
    fun `a device that never marked anything as seen keeps starting unread`() =
        withDatabase { database ->
            persist(database, notification(id = "first-ever", createdAt = 100))

            assertNull(stored(database, "first-ever").seenGloballyAt)
        }

    @Test
    fun `re-fetching an unread notification keeps it unread even when it predates the last mark-all`() =
        withDatabase { database ->
            seed(
                database,
                notification(id = "seen", createdAt = 1_000, seenAt = LAST_SEEN),
                notification(id = "still-unread", createdAt = 200, seenAt = null),
            )

            persist(database, notification(id = "still-unread", createdAt = 200))

            // Not stored for the first time, so the age rule must not touch it.
            assertNull(stored(database, "still-unread").seenGloballyAt)
        }

    @Test
    fun `re-fetching a seen notification keeps the moment it was seen`() =
        withDatabase { database ->
            seed(database, notification(id = "known", createdAt = 1_000, seenAt = LAST_SEEN))

            persist(database, notification(id = "known", createdAt = 1_000))

            assertEquals(LAST_SEEN, stored(database, "known").seenGloballyAt)
        }

    @Test
    fun `what another account has seen says nothing about this one`() =
        withDatabase { database ->
            seed(database, notification(id = "theirs", createdAt = 1_000, seenAt = LAST_SEEN, owner = OTHER_USER_ID))

            persist(database, notification(id = "mine", createdAt = 100))

            assertNull(stored(database, "mine").seenGloballyAt)
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun persist(database: CachingDatabase, vararg notifications: NotificationData) {
        RelayNotificationsResult(
            notifications = notifications.toList(),
            feedResponse = emptyFeedResponse(),
        ).persist(userId = USER_ID, group = NotificationGroup.ALL, database = database)
    }

    private suspend fun seed(database: CachingDatabase, vararg notifications: NotificationData) {
        database.notifications().upsertAll(notifications.toList())
        database.notificationGroupCrossRef().insertAll(
            notifications.map {
                NotificationGroupCrossRef(
                    notificationId = it.notificationId,
                    ownerId = it.ownerId,
                    groupKey = NotificationGroup.ALL.name,
                )
            },
        )
    }

    private suspend fun stored(database: CachingDatabase, id: String): NotificationData =
        database.notifications().findByIds(USER_ID, listOf(id)).single()

    /** What the screen's unread block would list, by id. */
    private suspend fun unseen(database: CachingDatabase): List<String> =
        database.notifications()
            .unseenByGroup(ownerId = USER_ID, groupKey = NotificationGroup.ALL.name, showFollows = true)
            .first()
            .map { it.data.notificationId }

    private fun notification(
        id: String,
        createdAt: Long,
        seenAt: Long? = null,
        owner: String = USER_ID,
    ) = NotificationData(
        notificationId = id,
        ownerId = owner,
        createdAt = createdAt,
        type = NotificationType.YOUR_POST_WAS_LIKED,
        seenGloballyAt = seenAt,
        actionUserId = "someone",
    )

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_first_store_seen_${counter++}.db"
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
        const val USER_ID = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val OTHER_USER_ID = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

        /** The moment the user last marked everything as seen. */
        const val LAST_SEEN = 5_000L
        var counter = 0
    }
}
