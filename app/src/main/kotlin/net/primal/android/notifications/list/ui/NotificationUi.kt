package net.primal.android.notifications.list.ui

import java.time.Instant
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.domain.links.CdnImage
import net.primal.domain.links.ReferencedStream
import net.primal.domain.notifications.NotificationType

data class NotificationUi(
    val notificationId: String,
    val ownerId: String,
    val notificationType: NotificationType,
    val createdAt: Instant,
    val actionUserId: String?,
    val actionUserDisplayName: String?,
    val reaction: String? = null,
    val actionUserInternetIdentifier: String? = null,
    val actionUserAvatarCdnImage: CdnImage? = null,
    val actionPost: FeedPostUi? = null,
    val actionUserSatsZapped: Long? = null,
    val referencedStream: ReferencedStream? = null,
    /**
     * How many notifications this row stands for.
     *
     * The seen feed collapses a day of follows in SQL, so one row can speak for many; the unseen
     * feed groups in memory and passes the rows themselves. Reading the count instead of the list
     * size lets one composable render both.
     */
    val groupCount: Int = 1,
)
