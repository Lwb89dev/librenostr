package net.primal.data.repository.mappers.local

import kotlin.time.Instant
import net.primal.data.local.dao.messages.PrivateThreadReplyData
import net.primal.data.local.dao.notifications.Notification as NotificationPO
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.utils.asEllipsizedNpub
import net.primal.domain.notifications.Notification as NotificationDO
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import net.primal.domain.posts.FeedPostStats
import net.primal.domain.posts.ThreadRelation
import net.primal.domain.streams.Stream

fun NotificationPO.asNotificationDO(): NotificationDO {
    return NotificationDO(
        notificationId = this.data.notificationId,
        ownerId = this.data.ownerId,
        createdAt = this.data.createdAt,
        type = this.data.type,
        seenGloballyAt = this.data.seenGloballyAt,
        actionUserId = this.data.actionUserId,
        actionPostId = this.data.actionPostId,
        satsZapped = this.data.satsZapped,
        reaction = this.data.reaction,
        groupCount = this.groupCount,
        actionByUser = this.actionByUser?.asProfileDataDO(),
        actionOnPost = this.privateFeedPost() ?: this.actionPost?.let { post ->
            FeedPost(
                eventId = post.postId,
                author = FeedPostAuthor(
                    authorId = post.authorId,
                    handle = post.authorId.asEllipsizedNpub(),
                    displayName = post.authorId.asEllipsizedNpub(),
                ),
                kind = post.kind,
                content = post.content,
                tags = post.tags,
                timestamp = Instant.fromEpochSeconds(post.createdAt),
                rawNostrEvent = post.raw,
                hashtags = post.hashtags,
                replyToAuthor = post.replyToAuthorId?.let {
                    FeedPostAuthor(
                        authorId = it,
                        handle = it.asEllipsizedNpub(),
                        displayName = it.asEllipsizedNpub(),
                    )
                },
                stats = FeedPostStats(
                    repliesCount = this.actionPostStats?.replies ?: 0,
                    userReplied = this.actionPostUserStats?.replied == true,
                    zapsCount = this.actionPostStats?.zaps ?: 0,
                    satsZapped = this.actionPostStats?.satsZapped ?: 0,
                    userZapped = this.actionPostUserStats?.zapped == true,
                    likesCount = this.actionPostStats?.likes ?: 0,
                    userLiked = this.actionPostUserStats?.liked == true,
                    repostsCount = this.actionPostStats?.reposts ?: 0,
                    userReposted = this.actionPostUserStats?.reposted == true,
                    userBookmarked = false,
                ),
                links = this.actionPostUris.map { it.asEventLinkDO() },
                nostrUris = this.actionPostNostrUris.map { it.asReferencedNostrUriDO() },
                eventZaps = emptyList(),
                bookmark = null,
            )
        },
        liveActivity = this.liveActivity?.let { stream ->
            Stream(
                aTag = stream.aTag,
                eventId = stream.eventId,
                eventAuthorId = stream.eventAuthorId,
                mainHostProfile = this.actionByUser?.asProfileDataDO(),
                mainHostId = stream.mainHostId,
                dTag = stream.dTag,
                title = stream.title,
                summary = stream.summary,
                imageUrl = stream.imageUrl,
                hashtags = stream.hashtags,
                streamingUrl = stream.streamingUrl,
                recordingUrl = stream.recordingUrl,
                startsAt = stream.startsAt,
                endsAt = stream.endsAt,
                status = stream.status,
                currentParticipants = stream.currentParticipants,
                totalParticipants = stream.totalParticipants,
                rawNostrEventJson = stream.raw,
                createdAt = stream.createdAt,
            )
        },
    )
}

/**
 * The gift-wrapped reply this notification points at, as a post, or null when it points at a
 * public note like every other notification type.
 *
 * See [NotificationPO.privateReplies] for why the relation is a list: the join can only match on
 * the event id, and every local account that received the same reply holds its own decrypted copy.
 */
private fun NotificationPO.privateFeedPost(): FeedPost? =
    this.privateReplies
        .firstOrNull { it.ownerId == this.data.ownerId }
        ?.asPrivateFeedPost(sender = this.actionByUser)

/**
 * Presents a decrypted private reply as a [FeedPost] so the notification list renders it with the
 * same note card as every other reply.
 *
 * [FeedPost.isPrivate] is what tells the UI to draw the lock and to hide the public actions — a
 * private reply cannot be reposted, zapped or bookmarked, because doing any of those would publish
 * a reference to an event that only exists inside gift wraps. [FeedPost.rawNostrEvent] stays empty
 * for the same reason: there is no signed event to hand out, only an unsigned rumor.
 */
private fun PrivateThreadReplyData.asPrivateFeedPost(sender: ProfileData?): FeedPost =
    FeedPost(
        eventId = this.eventId,
        author = FeedPostAuthor(
            authorId = this.senderId,
            handle = sender?.handle ?: this.senderId.asEllipsizedNpub(),
            displayName = sender?.displayName ?: sender?.handle ?: this.senderId.asEllipsizedNpub(),
            internetIdentifier = sender?.internetIdentifier,
            avatarCdnImage = sender?.avatarCdnImage,
            blossomServers = sender?.blossoms.orEmpty(),
        ),
        kind = NostrEventKind.ShortTextNote.value,
        content = this.content.decrypted,
        tags = emptyList(),
        timestamp = Instant.fromEpochSeconds(this.createdAt),
        rawNostrEvent = "",
        threadRelation = ThreadRelation(
            eventId = this.eventId,
            rootId = this.rootId.decrypted,
            parentId = this.parentId.decrypted,
        ),
        isPrivate = true,
    )
