package net.primal.data.local.dao.notifications

import androidx.room3.Entity
import androidx.room3.Index
import net.primal.domain.notifications.NotificationType

// ownerId is part of the key: one event can be a notification for two profiles on this device (a
// reply in a thread both are in, a follow of both on the same day), and keying on the id alone let
// the second profile's upsert steal the row from the first.
@Entity(
    primaryKeys = ["notificationId", "ownerId"],
    indices = [
        Index(value = ["ownerId", "createdAt"]),
    ],
)
data class NotificationData(
    val notificationId: String,
    val ownerId: String,
    val createdAt: Long,
    val type: NotificationType,
    val seenGloballyAt: Long? = null,
    val actionUserId: String? = null,
    val actionPostId: String? = null,
    val satsZapped: Long? = null,
    val reaction: String? = null,
)
