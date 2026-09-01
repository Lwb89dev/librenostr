package net.primal.data.repository.db

import androidx.paging.PagingSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.notifications.Notification
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.notifications.NotificationType
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * A day's new followers are one piece of news, not twenty rows.
 *
 * The collapse happens in the query rather than after paging on purpose: a page boundary must not
 * be able to split a day in two, and only the database sees every row of a day at once.
 */
class FollowDayGroupingTest {

    @Test
    fun `a day of follows is one row carrying the count`() =
        withDatabase { database ->
            seed(
                database,
                follow(id = "f1", actor = "alice", createdAt = DAY_ONE),
                follow(id = "f2", actor = "bob", createdAt = DAY_ONE + 3_600),
                follow(id = "f3", actor = "carol", createdAt = DAY_ONE + 7_200),
            )

            val rows = load(database)

            assertEquals(1, rows.size, "three followers on one day is one row")
            assertEquals(3, rows.first().groupCount)
        }

    @Test
    fun `rows left over from before the fix do not inflate the count`() =
        withDatabase { database ->
            // Databases written before follows were keyed by follower and day still hold one row
            // per unfollow-refollow cycle. The row must say how many people followed, not how
            // many events arrived.
            seed(
                database,
                follow(id = "old-1", actor = "spammer", createdAt = DAY_ONE),
                follow(id = "old-2", actor = "spammer", createdAt = DAY_ONE + 60),
                follow(id = "old-3", actor = "spammer", createdAt = DAY_ONE + 120),
                follow(id = "f1", actor = "alice", createdAt = DAY_ONE + 180),
            )

            val rows = load(database)

            assertEquals(1, rows.size)
            assertEquals(2, rows.single().groupCount, "two people followed, however many events said so")
        }

    @Test
    fun `the collapsed row speaks for the newest follower of that day`() =
        withDatabase { database ->
            // The row shows one avatar and one name, so it has to be the most recent one.
            seed(
                database,
                follow(id = "f1", actor = "alice", createdAt = DAY_ONE),
                follow(id = "f2", actor = "zoe", createdAt = DAY_ONE + 7_200),
            )

            val rows = load(database)

            assertEquals("zoe", rows.single().data.actionUserId)
        }

    @Test
    fun `follows on different days stay different rows`() =
        withDatabase { database ->
            seed(
                database,
                follow(id = "f1", actor = "alice", createdAt = DAY_ONE),
                follow(id = "f2", actor = "bob", createdAt = DAY_ONE + SECONDS_PER_DAY),
            )

            val rows = load(database)

            assertEquals(2, rows.size)
            assertEquals(listOf(1, 1), rows.map { it.groupCount })
        }

    @Test
    fun `the day boundary follows the reader's timezone`() =
        withDatabase { database ->
            // 23:00 and 01:00 UTC are the same local day for a reader two hours ahead, and the
            // row has to agree with the date printed next to it.
            seed(
                database,
                follow(id = "f1", actor = "alice", createdAt = DAY_ONE + 23 * 3_600),
                follow(id = "f2", actor = "bob", createdAt = DAY_ONE + 25 * 3_600),
            )

            val utc = load(database, utcOffsetSeconds = 0)
            val aheadByTwoHours = load(database, utcOffsetSeconds = 2 * 3_600)

            assertEquals(2, utc.size, "in UTC these fall on either side of midnight")
            assertEquals(1, aheadByTwoHours.size, "shifted forward they are the same evening")
        }

    @Test
    fun `content notifications are never collapsed`() =
        withDatabase { database ->
            // Two likes on the same day are two separate things that happened.
            seed(
                database,
                reaction(id = "r1", createdAt = DAY_ONE),
                reaction(id = "r2", createdAt = DAY_ONE + 60),
            )

            val rows = load(database)

            assertEquals(2, rows.size)
        }

    @Test
    fun `follows can be excluded entirely`() =
        withDatabase { database ->
            seed(
                database,
                follow(id = "f1", actor = "alice", createdAt = DAY_ONE),
                reaction(id = "r1", createdAt = DAY_ONE + 60),
            )

            val rows = load(database, showFollows = false)

            assertEquals(1, rows.size)
            assertEquals(NotificationType.YOUR_POST_WAS_LIKED, rows.single().data.type)
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun load(
        database: CachingDatabase,
        showFollows: Boolean = true,
        utcOffsetSeconds: Long = 0,
    ): List<Notification> {
        val source = database.notifications().seenByGroupPaged(
            ownerId = USER_ID,
            groupKey = GROUP_KEY,
            showFollows = showFollows,
            utcOffsetSeconds = utcOffsetSeconds,
        )
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
        )
        return (result as? PagingSource.LoadResult.Page)?.data ?: fail("load failed: $result")
    }

    private suspend fun seed(database: CachingDatabase, vararg notifications: NotificationData) {
        database.notifications().upsertAll(data = notifications.toList())
        database.notificationGroupCrossRef().insertAll(
            refs = notifications.map {
                NotificationGroupCrossRef(
                    notificationId = it.notificationId,
                    ownerId = USER_ID,
                    groupKey = GROUP_KEY,
                )
            },
        )
    }

    private fun follow(
        id: String,
        actor: String,
        createdAt: Long,
    ) = NotificationData(
        notificationId = id,
        ownerId = USER_ID,
        createdAt = createdAt,
        type = NotificationType.NEW_USER_FOLLOWED_YOU,
        // Only seen rows reach this query; unseen ones are grouped in memory instead.
        seenGloballyAt = createdAt,
        actionUserId = actor,
    )

    private fun reaction(id: String, createdAt: Long) =
        NotificationData(
            notificationId = id,
            ownerId = USER_ID,
            createdAt = createdAt,
            type = NotificationType.YOUR_POST_WAS_LIKED,
            seenGloballyAt = createdAt,
            actionUserId = "alice",
            actionPostId = "note-1",
        )

    private fun withDatabase(block: suspend (CachingDatabase) -> Unit) =
        runBlocking {
            val databaseName = "primal_follow_day_grouping_${counter++}.db"
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
        const val USER_ID = "user-pubkey"
        const val GROUP_KEY = "ALL"
        const val SECONDS_PER_DAY = 86_400L

        /** An exact UTC midnight, so the boundary cases are unambiguous. */
        const val DAY_ONE = 1_756_684_800L
        var counter = 0
    }
}
