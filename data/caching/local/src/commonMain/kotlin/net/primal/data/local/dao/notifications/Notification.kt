package net.primal.data.local.dao.notifications

import androidx.room3.Embedded
import androidx.room3.Relation
import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.dao.events.EventUri
import net.primal.data.local.dao.events.EventUriNostr
import net.primal.data.local.dao.events.EventUserStats
import net.primal.data.local.dao.messages.PrivateThreadReplyData
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.dao.streams.StreamData
import net.primal.domain.notifications.NotificationType

data class Notification(
    @Embedded
    val data: NotificationData,

    @Relation(entityColumns = ["ownerId"], parentColumns = ["actionUserId"])
    val actionByUser: ProfileData?,

    @Relation(entityColumns = ["postId"], parentColumns = ["actionPostId"])
    val actionPost: PostData? = null,

    @Relation(entityColumns = ["eventId"], parentColumns = ["actionPostId"])
    val actionPostStats: EventStats? = null,

    @Relation(entityColumns = ["eventId"], parentColumns = ["actionPostId"])
    val actionPostUserStats: EventUserStats? = null,

    @Relation(entityColumns = ["eventId"], parentColumns = ["actionPostId"])
    val actionPostUris: List<EventUri> = emptyList(),

    @Relation(entityColumns = ["eventId"], parentColumns = ["actionPostId"])
    val actionPostNostrUris: List<EventUriNostr> = emptyList(),

    @Relation(entityColumns = ["aTag"], parentColumns = ["actionPostId"])
    val liveActivity: StreamData? = null,

    /**
     * The gift-wrapped replies matching this notification's target id, across every local account.
     *
     * A private reply is never written to [PostData] — that table feeds public queries — so
     * [actionPost] is always null for [NotificationType.YOUR_POST_WAS_PRIVATELY_REPLIED_TO] and
     * the row would render as a bare header with no body. This relation gives the mapper the
     * decrypted reply to build a post out of, without the private text ever leaving the
     * at-rest-encrypted table it lives in.
     *
     * A list rather than a single row because [PrivateThreadReplyData] is keyed by
     * (ownerId, eventId) and a `@Relation` can only join on one column: on a device with several
     * accounts the same reply id exists once per account that received it. The mapper picks the
     * row whose owner matches this notification's, so one account never renders another's copy.
     */
    @Relation(entityColumns = ["eventId"], parentColumns = ["actionPostId"])
    val privateReplies: List<PrivateThreadReplyData> = emptyList(),

    /**
     * How many rows this one stands for.
     *
     * Follows are collapsed to one row per day by the query, because a day's new followers are
     * one piece of news and not twenty. Everything else stands for itself and counts one.
     */
    val groupCount: Int = 1,
)
