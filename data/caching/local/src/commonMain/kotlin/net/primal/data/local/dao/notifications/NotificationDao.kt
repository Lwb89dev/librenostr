package net.primal.data.local.dao.notifications

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import net.primal.data.local.db.NotificationPagingSourceDaoReturnTypeConverter

@Dao
@DaoReturnTypeConverters(NotificationPagingSourceDaoReturnTypeConverter::class)
interface NotificationDao {

    /**
     * The seen feed, with follows collapsed to one row per local day.
     *
     * Grouping happens here rather than after paging because a page boundary must not be able to
     * split a day in two: the rows that belong together are decided before anything is paged.
     * [utcOffsetSeconds] shifts the day boundary to the reader's timezone, so the bucket matches
     * the date the screen prints next to it.
     *
     * MAX(n.createdAt) is what makes the remaining bare columns come from the newest row of each
     * group, so a collapsed day is represented by its most recent follower.
     *
     * The count is of people rather than of rows. Rows written before follows were keyed by
     * follower and day are still duplicated in existing databases, and counting them would say
     * fifteen people followed you on a day when three did.
     */
    @Transaction
    @Query(
        """
            SELECT n.*, COUNT(DISTINCT n.actionUserId) AS groupCount, MAX(n.createdAt) AS newestCreatedAt
            FROM NotificationData n
            INNER JOIN NotificationGroupCrossRef g
                ON n.notificationId = g.notificationId AND n.ownerId = g.ownerId
            WHERE n.ownerId = :ownerId
              AND g.groupKey = :groupKey
              AND n.seenGloballyAt IS NOT NULL
              AND (:showFollows OR n.type != 'NEW_USER_FOLLOWED_YOU')
            GROUP BY CASE
                WHEN n.type = 'NEW_USER_FOLLOWED_YOU'
                    THEN 'follow-day-' || ((n.createdAt + :utcOffsetSeconds) / 86400)
                ELSE n.notificationId
            END
            ORDER BY n.createdAt DESC, n.notificationId DESC
        """,
    )
    fun seenByGroupPaged(
        ownerId: String,
        groupKey: String,
        showFollows: Boolean,
        utcOffsetSeconds: Long,
    ): PagingSource<Int, Notification>

    @Transaction
    @Query(
        """
            SELECT n.*, 1 AS groupCount FROM NotificationData n
            INNER JOIN NotificationGroupCrossRef g
                ON n.notificationId = g.notificationId AND n.ownerId = g.ownerId
            WHERE n.ownerId = :ownerId
              AND g.groupKey = :groupKey
              AND n.seenGloballyAt IS NULL
              AND (:showFollows OR n.type != 'NEW_USER_FOLLOWED_YOU')
            ORDER BY n.createdAt DESC, n.notificationId DESC
        """,
    )
    fun unseenByGroup(
        ownerId: String,
        groupKey: String,
        showFollows: Boolean,
    ): Flow<List<Notification>>

    @Query(
        """
            SELECT n.* FROM NotificationData n
            INNER JOIN NotificationGroupCrossRef g
                ON n.notificationId = g.notificationId AND n.ownerId = g.ownerId
            WHERE n.ownerId = :ownerId AND g.groupKey = :groupKey
            ORDER BY n.createdAt DESC, n.notificationId DESC LIMIT 1
        """,
    )
    suspend fun firstByGroup(ownerId: String, groupKey: String): NotificationData?

    @Query(
        """
            SELECT n.* FROM NotificationData n
            INNER JOIN NotificationGroupCrossRef g
                ON n.notificationId = g.notificationId AND n.ownerId = g.ownerId
            WHERE n.ownerId = :ownerId AND g.groupKey = :groupKey
            ORDER BY n.createdAt ASC, n.notificationId ASC LIMIT 1
        """,
    )
    suspend fun lastByGroup(ownerId: String, groupKey: String): NotificationData?

    @Query("UPDATE NotificationData SET seenGloballyAt = :seenAt WHERE seenGloballyAt IS NULL AND ownerId = :ownerId")
    suspend fun markAllUnseenNotificationsAsSeen(ownerId: String, seenAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(data: List<NotificationData>)

    @Query("SELECT * FROM NotificationData WHERE ownerId = :ownerId AND notificationId IN (:notificationIds)")
    suspend fun findByIds(ownerId: String, notificationIds: List<String>): List<NotificationData>

    @Query("DELETE FROM NotificationData WHERE ownerId = :ownerId")
    suspend fun deleteAllByOwnerId(ownerId: String)
}
