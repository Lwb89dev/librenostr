package net.primal.data.repository.notifications

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

    // Whether a notification has already been seen is local state. Re-fetching the same event
    // must not mark it unread again.
    val existing = database.notifications()
        .findByIds(userId, notifications.map { it.notificationId })
        .associateBy { it.notificationId }
    val tagged = notifications.map { notification ->
        notification.copy(seenGloballyAt = existing[notification.notificationId]?.seenGloballyAt)
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
