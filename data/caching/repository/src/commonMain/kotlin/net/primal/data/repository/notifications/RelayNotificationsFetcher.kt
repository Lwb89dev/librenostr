package net.primal.data.repository.notifications

import io.github.aakira.napier.Napier
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.repository.feed.toFeedResponse
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Builds the notification stream from standard Nostr events.
 *
 * There is no NIP defining a notification event: clients derive notifications from reactions,
 * replies, reposts, zaps and follow lists. Keeping this derivation here means the app no longer
 * depends on Primal's synthetic kind 10_000_110 notification payload.
 */
internal class RelayNotificationsFetcher(
    private val querier: RelayEventQuerier,
) {

    suspend fun fetch(
        userId: String,
        group: NotificationGroup,
        limit: Int,
        until: Long? = null,
    ): RelayNotificationsResult {
        // Notifications can be queried directly by the user's `p` tag. The previous
        // implementation downloaded hundreds of the user's posts and then queried every post
        // in chunks, making a single screen open issue dozens of sequential relay requests.
        val events = coroutineScope {
            val follows = async {
                query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.FollowList.value),
                        pubkeyTags = listOf(userId),
                        limit = limit,
                        until = until,
                    ),
                )
            }
            val interactions = listOf(
                NostrEventKind.Reaction.value,
                NostrEventKind.ShortTextNoteRepost.value,
                NostrEventKind.ShortTextNote.value,
                NostrEventKind.Zap.value,
            ).map { kind ->
                async {
                    query(
                        RelayFilter(
                            kinds = listOf(kind),
                            pubkeyTags = listOf(userId),
                            limit = limit,
                            until = until,
                        ),
                    )
                }
            }

            buildList {
                addAll(follows.await())
                interactions.awaitAll().forEach(::addAll)
            }.distinctBy { it.id }
        }

        val notifications = events.mapNotNull { it.asNotification(userId) }
            .filter { it.type.belongsTo(group) }
            .sortedByDescending { it.createdAt }
            .take(limit)

        Napier.i {
            "Relay notifications fetched: events=${events.size}, " +
                "notifications=${notifications.size}, group=${group.name}"
        }

        val actors = notifications.mapNotNull { it.actionUserId }.distinct()
        val metadata = if (actors.isEmpty()) {
            emptyList()
        } else {
            query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = actors,
                    limit = actors.size,
                ),
            ).latestMetadataByPubkey()
        }
        val contentEvents = events.filter { it.kind == NostrEventKind.ShortTextNote.value ||
            it.kind == NostrEventKind.ShortTextNoteRepost.value || it.kind == NostrEventKind.Zap.value }
        return RelayNotificationsResult(
            notifications = notifications,
            feedResponse = (contentEvents + metadata).distinctBy { it.id }.toFeedResponse(metadata),
        )
    }

    private suspend fun query(filter: RelayFilter): List<NostrEvent> =
        // Timeout and user/fallback selection are handled by RelaysSocketManager. Keeping a
        // second timeout here would cancel the manager exactly while it switches to fallback.
        runCatching { querier.query(filter) }.getOrDefault(emptyList<NostrEvent>())

    private fun NostrEvent.asNotification(userId: String): NotificationData? {
        if (pubKey == userId) return null
        val target = tags.eventIdTagValues().firstOrNull()
        val type = when (kind) {
            NostrEventKind.FollowList.value -> NotificationType.NEW_USER_FOLLOWED_YOU
            NostrEventKind.Reaction.value -> if (target != null) NotificationType.YOUR_POST_WAS_LIKED else null
            NostrEventKind.ShortTextNoteRepost.value -> if (target != null) NotificationType.YOUR_POST_WAS_REPOSTED else null
            NostrEventKind.Zap.value -> if (target != null) NotificationType.YOUR_POST_WAS_ZAPPED else null
            NostrEventKind.ShortTextNote.value -> when {
                target != null -> NotificationType.YOUR_POST_WAS_REPLIED_TO
                tags.pubkeyTagValues().contains(userId) -> NotificationType.YOU_WERE_MENTIONED_IN_POST
                else -> null
            }
            else -> null
        } ?: return null
        val amount = if (type == NotificationType.YOUR_POST_WAS_ZAPPED) {
            tags.findFirstZapAmount()?.toLongOrNull()?.let { if (it >= 1000) it / 1000 else it }
        } else {
            null
        }
        return NotificationData(
            notificationId = id,
            ownerId = userId,
            createdAt = createdAt,
            type = type,
            actionUserId = pubKey,
            actionPostId = when (type) {
                NotificationType.YOUR_POST_WAS_REPLIED_TO,
                NotificationType.YOU_WERE_MENTIONED_IN_POST -> id
                else -> target
            },
            satsZapped = amount,
            reaction = if (type == NotificationType.YOUR_POST_WAS_LIKED) content else null,
        )
    }

    private fun NotificationType.belongsTo(group: NotificationGroup): Boolean = when (group) {
        NotificationGroup.ALL -> true
        NotificationGroup.ZAPS -> this == NotificationType.YOUR_POST_WAS_ZAPPED
        NotificationGroup.REPLIES -> this == NotificationType.YOUR_POST_WAS_REPLIED_TO
        NotificationGroup.MENTIONS -> this == NotificationType.YOU_WERE_MENTIONED_IN_POST
        NotificationGroup.REPOSTS -> this == NotificationType.YOUR_POST_WAS_REPOSTED
    }

}

internal data class RelayNotificationsResult(
    val notifications: List<NotificationData>,
    val feedResponse: net.primal.data.remote.api.feed.model.FeedResponse,
)
