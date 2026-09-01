package net.primal.data.repository.notifications

import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.feed.toFeedResponse
import net.primal.data.repository.mappers.remote.extractZapRequestOrNull
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstZapAmount
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType

/**
 * Builds the notification stream from standard Nostr events.
 *
 * There is no NIP defining a notification event: clients derive notifications from reactions,
 * replies, reposts, zaps and follow lists. Keeping this derivation here means the app no longer
 * depends on Primal's synthetic kind 10_000_110 notification payload.
 *
 * Everything the screen needs is reachable with a single REQ, because a Nostr filter takes a
 * list of kinds. The previous version issued one REQ per kind, which was both slow and wrong:
 * each kind got its own `limit`, so the merged page was whichever kind happened to be busiest
 * and the rest were truncated away. Asking for one chronological page of mixed kinds is what
 * other clients do, and it is why they returned more notifications in less time.
 */
internal class RelayNotificationsFetcher(
    private val querier: RelayEventQuerier,
    private val cache: LocalEventCache? = null,
) {

    suspend fun fetch(
        userId: String,
        group: NotificationGroup,
        limit: Int,
        until: Long? = null,
    ): RelayNotificationsResult {
        // Only the kinds that can produce a notification in this group are requested. Opening the
        // Zaps tab used to download reactions, replies, reposts and follow lists as well, only to
        // discard them after the group filter.
        val events = query(
            RelayFilter(
                kinds = group.notificationKinds(),
                pubkeyTags = listOf(userId),
                limit = limit,
                until = until,
            ),
        )

        val notifications = events.mapNotNull { it.asNotification(userId) }
            .filter { it.type.belongsTo(group) }
            .distinctBy { it.notificationId }
            .sortedWith(compareByDescending<NotificationData> { it.createdAt }.thenByDescending { it.notificationId })
            .take(limit)

        Napier.i {
            "Relay notifications fetched: events=${events.size}, " +
                "notifications=${notifications.size}, group=${group.name}"
        }

        // Interaction events point at the original note through their `e` tag. Fetch those
        // referenced events as well, otherwise a relay-only notification row has no note body
        // to render as a useful preview (likes/zaps/reposts especially).
        //
        // The referenced notes are the user's own, so their authors add nothing to the actor
        // set: both queries can run at once instead of chaining metadata behind the notes.
        val referencedEventIds = notifications.mapNotNull { it.actionPostId }.distinct()
        val actors = notifications.mapNotNull { it.actionUserId }.distinct()

        val (referencedEvents, metadata) = coroutineScope {
            val referenced = async {
                // The notes a notification points at are usually already stored by the feed.
                // The cached ones are folded back in, not dropped: they still have to reach the
                // response so the row renders its preview.
                val cached = cache?.partitionKnownEventIds(referencedEventIds)
                val missing = cached?.missing ?: referencedEventIds
                val known = cached?.known.orEmpty()
                if (missing.isEmpty()) {
                    known
                } else {
                    known + query(
                        RelayFilter(
                            ids = missing,
                            kinds = CONTENT_KINDS,
                            limit = missing.size,
                        ),
                    )
                }
            }
            val profiles = async {
                val wanted = cache?.claimMetadataPubkeys(actors) ?: actors
                if (wanted.isEmpty()) {
                    emptyList()
                } else {
                    fetchMetadata(wanted)
                }
            }
            listOf(referenced, profiles).awaitAll()
            referenced.await() to profiles.await()
        }

        val contentEvents = (events + referencedEvents)
            .filter { it.kind in CONTENT_KINDS || it.kind == NostrEventKind.Zap.value }

        return RelayNotificationsResult(
            notifications = notifications,
            feedResponse = (contentEvents + metadata).distinctBy { it.id }.toFeedResponse(metadata),
            // Pagination must key off what the relays returned, not off the group-filtered rows.
            // Judging by the filtered count declared the end of the list as soon as a tab was
            // sparse — the Zaps tab stopped after its first page even with older zaps available.
            relayEventCount = events.size,
        )
    }

    private fun NotificationGroup.notificationKinds(): List<Int> =
        when (this) {
            NotificationGroup.ALL -> listOf(
                NostrEventKind.ShortTextNote.value,
                NostrEventKind.FollowList.value,
                NostrEventKind.ShortTextNoteRepost.value,
                NostrEventKind.Reaction.value,
                NostrEventKind.Zap.value,
            )
            NotificationGroup.ZAPS -> listOf(NostrEventKind.Zap.value)
            NotificationGroup.REPLIES, NotificationGroup.MENTIONS ->
                listOf(NostrEventKind.ShortTextNote.value)
            NotificationGroup.REPOSTS -> listOf(NostrEventKind.ShortTextNoteRepost.value)
        }

    private suspend fun query(filter: RelayFilter): List<NostrEvent> =
        // Timeout and user/fallback selection are handled by RelaysSocketManager. Keeping a
        // second timeout here would cancel the manager exactly while it switches to fallback.
        runCatching { querier.query(filter) }.getOrDefault(emptyList<NostrEvent>())

    private fun NostrEvent.asNotification(userId: String): NotificationData? {
        val target = tags.eventIdTagValues().firstOrNull()
        val type = when (kind) {
            NostrEventKind.FollowList.value -> NotificationType.NEW_USER_FOLLOWED_YOU
            NostrEventKind.Reaction.value -> if (target != null) NotificationType.YOUR_POST_WAS_LIKED else null
            NostrEventKind.ShortTextNoteRepost.value ->
                if (target != null) NotificationType.YOUR_POST_WAS_REPOSTED else null
            NostrEventKind.Zap.value -> if (target != null) NotificationType.YOUR_POST_WAS_ZAPPED else null
            NostrEventKind.ShortTextNote.value -> when {
                target != null -> NotificationType.YOUR_POST_WAS_REPLIED_TO
                tags.pubkeyTagValues().contains(userId) -> NotificationType.YOU_WERE_MENTIONED_IN_POST
                else -> null
            }
            else -> null
        } ?: return null

        // A NIP-57 receipt is signed by the recipient's LNURL server, not by the person who
        // zapped. The sender is the author of the kind 9734 request embedded in `description`;
        // reading `pubKey` here credited every zap to the payment provider.
        val actionUserId = if (type == NotificationType.YOUR_POST_WAS_ZAPPED) {
            extractZapRequestOrNull()?.pubKey ?: return null
        } else {
            pubKey
        }
        if (actionUserId == userId) return null

        val amount = if (type == NotificationType.YOUR_POST_WAS_ZAPPED) {
            tags.findFirstZapAmount()?.toLongOrNull()?.let { if (it >= MILLISATS_PER_SAT) it / MILLISATS_PER_SAT else it }
        } else {
            null
        }
        return NotificationData(
            notificationId = notificationIdFor(type = type, actionUserId = actionUserId),
            ownerId = userId,
            createdAt = createdAt,
            type = type,
            actionUserId = actionUserId,
            actionPostId = when (type) {
                NotificationType.YOUR_POST_WAS_REPLIED_TO,
                NotificationType.YOU_WERE_MENTIONED_IN_POST,
                -> id
                else -> target
            },
            satsZapped = amount,
            reaction = if (type == NotificationType.YOUR_POST_WAS_LIKED) content else null,
        )
    }

    /**
     * Identity of a notification row, which for follows is not the identity of the event.
     *
     * A follow notification comes from a kind 3 list, and a list is republished in full every
     * time it changes. Accounts that follow and unfollow in a loop therefore emit a new event id
     * on every cycle, and keying rows by event id turned one bot into dozens of identical
     * "followed you" rows within the same minute — seven from a single account in the case that
     * prompted this.
     *
     * Keying a follow by who did it and on what day collapses the loop into the one fact it
     * carries: this person followed you today. A genuine follow months later is a different day
     * and stays its own row. Every other kind is content, where the event id is the right
     * identity and re-publishing does not happen.
     */
    private fun NostrEvent.notificationIdFor(type: NotificationType, actionUserId: String): String =
        when (type) {
            NotificationType.NEW_USER_FOLLOWED_YOU ->
                "$FOLLOW_ID_PREFIX$actionUserId:${createdAt / SECONDS_PER_DAY}"
            else -> id
        }

    private fun NotificationType.belongsTo(group: NotificationGroup): Boolean =
        when (group) {
            NotificationGroup.ALL -> true
            NotificationGroup.ZAPS -> this == NotificationType.YOUR_POST_WAS_ZAPPED
            NotificationGroup.REPLIES -> this == NotificationType.YOUR_POST_WAS_REPLIED_TO
            NotificationGroup.MENTIONS -> this == NotificationType.YOU_WERE_MENTIONED_IN_POST
            NotificationGroup.REPOSTS -> this == NotificationType.YOUR_POST_WAS_REPOSTED
        }

    /**
     * Fetches kind 0 for [wanted] and gives back the claims that came back empty.
     *
     * A claim that is kept after a failed or empty request is how an actor stays a raw npub for
     * the rest of the session: nothing would ever ask a second time.
     */
    private suspend fun fetchMetadata(wanted: List<String>): List<NostrEvent> {
        val metadata = query(
            RelayFilter(
                kinds = listOf(NostrEventKind.Metadata.value),
                authors = wanted,
                limit = wanted.size,
            ),
        ).latestMetadataByPubkey()
        cache?.releaseMetadataPubkeys(wanted - metadata.map { it.pubKey }.toSet())
        return metadata
    }

    private companion object {
        const val MILLISATS_PER_SAT = 1000L
        const val SECONDS_PER_DAY = 86_400L
        const val FOLLOW_ID_PREFIX = "follow:"

        val CONTENT_KINDS = listOf(
            NostrEventKind.ShortTextNote.value,
            NostrEventKind.ShortTextNoteRepost.value,
            NostrEventKind.PictureNote.value,
            NostrEventKind.LongFormContent.value,
        )
    }
}

internal data class RelayNotificationsResult(
    val notifications: List<NotificationData>,
    val feedResponse: net.primal.data.remote.api.feed.model.FeedResponse,
    val relayEventCount: Int = 0,
)
