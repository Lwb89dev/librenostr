package net.primal.domain.notifications

import androidx.paging.PagingData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.NostrEvent

interface NotificationRepository {

    fun observeUnseenNotifications(
        ownerId: String,
        group: NotificationGroup,
        showFollows: Boolean = true,
    ): Flow<List<Notification>>

    /**
     * The seen feed.
     *
     * [utcOffsetSeconds] is supplied by the caller rather than read here, because the day a
     * follow belongs to is the reader's day and only the UI layer knows which timezone that is.
     */
    fun observeSeenNotifications(
        userId: String,
        group: NotificationGroup,
        showFollows: Boolean = true,
        utcOffsetSeconds: Long = 0,
    ): Flow<PagingData<Notification>>

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun markAllNotificationsAsSeen(authorization: NostrEvent)

    /** Marks the local cache as read without contacting any proprietary notification service. */
    suspend fun markAllNotificationsAsSeenLocally(userId: String)

    /**
     * Pulls notifications at session start, newest first, then walks back through older pages.
     *
     * Without this nothing is fetched until the tab is opened, so the unread dot could only ever
     * appear after the user had already gone looking.
     */
    suspend fun syncNotifications(userId: String, backfillPages: Int = DEFAULT_BACKFILL_PAGES)

    companion object {
        /** Bounded on purpose: a public relay's history is effectively endless. */
        const val DEFAULT_BACKFILL_PAGES = 3
    }
}
