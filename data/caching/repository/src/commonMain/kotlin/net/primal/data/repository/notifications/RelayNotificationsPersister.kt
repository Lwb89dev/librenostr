package net.primal.data.repository.notifications

import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.feed.processors.persistToDatabaseAsTransaction
import net.primal.domain.notifications.NotificationGroup
import net.primal.shared.data.local.db.withTransaction

/**
 * Writes a page of relay notifications to the database.
 *
 * Extracted so the paging mediator and the session-start sync agree on what persisting means:
 * the same seen-timestamp preservation, the same group cross-refs, the same feed payload. Two
 * copies of this would drift, and the failure would look like notifications that appear only
 * when the tab is opened.
 */
internal suspend fun RelayNotificationsResult.persist(
    userId: String,
    group: NotificationGroup,
    database: CachingDatabase,
) {
    if (notifications.isEmpty()) return

    val existing = database.notifications()
        .findByIds(userId, notifications.map { it.notificationId })
        .associateBy { it.notificationId }
    val lastSeenAt = database.notifications().latestSeenAt(ownerId = userId)

    val tagged = notifications.map { notification ->
        val known = existing[notification.notificationId]
        notification.copy(
            // Whether a notification has already been seen is local state. Re-fetching the same
            // event must not mark it unread again, and must not mark an unread one as read.
            seenGloballyAt = if (known != null) known.seenGloballyAt else notification.seenAtFirstStore(lastSeenAt),
        )
    }

    feedResponse.persistToDatabaseAsTransaction(userId = userId, database = database)
    database.withTransaction {
        database.notifications().upsertAll(tagged)
        database.notificationGroupCrossRef().insertAll(
            tagged.map {
                NotificationGroupCrossRef(
                    notificationId = it.notificationId,
                    ownerId = userId,
                    groupKey = group.name,
                )
            },
        )
    }
}

/**
 * The seen state of a notification this device has never stored before.
 *
 * A row stored for the first time used to always start unread, whatever its age. That is right for
 * something that just happened and wrong for history: scrolling down (or a refresh that fills a
 * gap left by a relay that timed out) pulls in notifications from weeks ago, which then landed in
 * the unread block above everything else. The screen opened with three-week-old likes on top and
 * the notifications from today pushed below them, until "mark all as read" moved them into their
 * chronological place.
 *
 * Anything older than the last time the user marked everything as seen was already behind them
 * then, so it starts as seen at that moment. Only what is newer than that is news. A device that
 * has never marked anything ([lastSeenAt] null) has no such point and keeps starting unread.
 */
private fun NotificationData.seenAtFirstStore(lastSeenAt: Long?): Long? =
    lastSeenAt?.takeIf { createdAt <= it }
