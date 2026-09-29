package net.primal.data.repository.notifications

import io.github.aakira.napier.Napier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.core.utils.toLong
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.feed.asReferencedPrimalEvent
import net.primal.data.repository.feed.referencedNoteIds
import net.primal.data.repository.feed.toFeedResponse
import net.primal.data.repository.mappers.remote.extractZapRequestOrNull
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstBolt11
import net.primal.domain.nostr.findFirstZapAmount
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.LnInvoiceUtils
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType

/** The flat event set [RelayNotificationsFetcher.fetchEvents] resolves for one page. */
internal data class NotificationEvents(
    val notifications: List<NotificationData>,
    /** Events + their referenced notes + quoted notes — content, no kind-0. */
    val contentEvents: List<NostrEvent>,
    val actors: List<String>,
    /** Quoted-note authors, already excluding anyone already in [actors]. */
    val quotedAuthors: List<String>,
    /** The quoted notes specifically, for the `referencedEvents` field of a [FeedResponse]. */
    val quotedNotes: List<NostrEvent>,
    val relayEventCount: Int,
)

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
        val events = fetchEvents(userId = userId, group = group, limit = limit, until = until)
        val metadata = fetchMetadataFor(events)
        return RelayNotificationsResult(
            notifications = events.notifications,
            feedResponse = (events.contentEvents + metadata).distinctBy { it.id }
                .toFeedResponse(metadata, referencedEvents = events.quotedNotes.map { it.asReferencedPrimalEvent() }),
            relayEventCount = events.relayEventCount,
        )
    }

    /**
     * The notifications and the note content they need to render, before profile metadata is
     * resolved for any of it — see [fetchMetadataFor]. Split from the old [fetch] the same way
     * [RelayThreadFetcher]/[net.primal.data.repository.feed.RelayNotesFeedFetcher] split theirs:
     * so a caller can persist notification rows and note bodies the moment they're known, instead
     * of waiting for kind 0 (actors, and the authors of anything quoted) to also land first.
     */
    suspend fun fetchEvents(
        userId: String,
        group: NotificationGroup,
        limit: Int,
        until: Long? = null,
    ): NotificationEvents {
        val page = fetchPageEvents(userId = userId, group = group, limit = limit, until = until)
        val events = page.events

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
        // to render as a useful preview (likes/zaps/reposts especially). The referenced notes are
        // the user's own, so their authors add nothing to the actor set.
        val referencedEventIds = notifications.mapNotNull { it.actionPostId }.distinct()
        val actors = notifications.mapNotNull { it.actionUserId }.distinct()

        // The notes a notification points at are usually already stored by the feed. The cached
        // ones are folded back in, not dropped: they still have to reach the response so the row
        // renders its preview.
        val cached = cache?.partitionKnownEventIds(referencedEventIds)
        val missing = cached?.missing ?: referencedEventIds
        val known = cached?.known.orEmpty()
        val referencedEvents = if (missing.isEmpty()) {
            known
        } else {
            known + query(RelayFilter(ids = missing, kinds = CONTENT_KINDS, limit = missing.size))
        }

        val contentEventsBeforeQuotes = (events + referencedEvents)
            .filter { it.kind in CONTENT_KINDS || it.kind == NostrEventKind.Zap.value }

        // A mention/reply notification's target note can itself quote or mention a further note
        // (a `q` tag, or a bare `nostr:note1…`/`nevent1…` in its content) — one level deeper than
        // actionPostId reaches, so without this that nested reference showed "Mentioned event not
        // found" here even when it rendered fine in the note feed/thread.
        val quotedNotes = fetchQuotedNoteContent(contentEventsBeforeQuotes)
        val quotedAuthors = quotedNotes.map { it.pubKey }.distinct().filterNot { it in actors }

        return NotificationEvents(
            notifications = notifications,
            contentEvents = (contentEventsBeforeQuotes + quotedNotes).distinctBy { it.id },
            actors = actors,
            quotedAuthors = quotedAuthors,
            quotedNotes = quotedNotes,
            // Pagination must key off what the relays returned, not off the group-filtered rows.
            // Judging by the filtered count declared the end of the list as soon as a tab was
            // sparse — the Zaps tab stopped after its first page even with older zaps available.
            relayEventCount = if (page.isFull) maxOf(events.size, limit) else events.size,
        )
    }

    /** Kind 0 for every actor and every quoted note's author, run concurrently. */
    suspend fun fetchMetadataFor(events: NotificationEvents): List<NostrEvent> {
        val (actorMetadata, quotedMetadata) = coroutineScope {
            val actorAsync = async { fetchMetadataFor(pubkeys = events.actors) }
            val quotedAsync = async { fetchMetadataFor(pubkeys = events.quotedAuthors) }
            actorAsync.await() to quotedAsync.await()
        }
        return actorMetadata + quotedMetadata
    }

    private suspend fun fetchMetadataFor(pubkeys: List<String>): List<NostrEvent> {
        val wanted = cache?.claimMetadataPubkeys(pubkeys) ?: pubkeys
        return if (wanted.isEmpty()) emptyList() else fetchMetadata(wanted)
    }

    /**
     * Just the notes a page's content quotes — without the authors the quoted note is stored but
     * cannot be shown: a quote card needs the name to head it, so it stayed "Mentioned event not
     * found" although the note itself had been downloaded. Author resolution is
     * [fetchMetadataFor]'s job now, not this function's.
     */
    private suspend fun fetchQuotedNoteContent(contentEvents: List<NostrEvent>): List<NostrEvent> {
        val knownIds = contentEvents.map { it.id }.toSet()
        val missingQuotedIds = contentEvents.referencedNoteIds().filterNot { it in knownIds }
        if (missingQuotedIds.isEmpty()) return emptyList()
        return query(RelayFilter(ids = missingQuotedIds, kinds = CONTENT_KINDS, limit = missingQuotedIds.size))
    }

    /**
     * The raw events one page of notifications is derived from, and whether the relays filled it.
     *
     * Content and follow lists are requested apart, see the comment below for why.
     */
    private suspend fun fetchPageEvents(
        userId: String,
        group: NotificationGroup,
        limit: Int,
        until: Long?,
    ): PageEvents {
        // Only the kinds that can produce a notification in this group are requested. Opening the
        // Zaps tab used to download reactions, replies, reposts and follow lists as well, only to
        // discard them after the group filter.
        val kinds = group.notificationKinds()
        val (mainEvents, followEvents) = coroutineScope {
            val content = async {
                query(
                    RelayFilter(
                        kinds = kinds - NostrEventKind.FollowList.value,
                        pubkeyTags = listOf(userId),
                        limit = limit,
                        until = until,
                    ),
                )
            }
            val follows = async {
                if (NostrEventKind.FollowList.value !in kinds) {
                    emptyList()
                } else {
                    query(
                        RelayFilter(
                            kinds = listOf(NostrEventKind.FollowList.value),
                            pubkeyTags = listOf(userId),
                            limit = FOLLOW_LIST_PAGE_LIMIT,
                            until = until,
                        ),
                    )
                }
            }
            content.await() to follows.await()
        }

        // A follow notification is a whole kind 3 contact list, and one list can weigh a couple of
        // hundred kilobytes. Bots that follow/unfollow in a loop republish it in full every cycle,
        // so mixing kind 3 into the same `limit = 200` request let a single account fill the page
        // with hundreds of these: every relay streamed tens of megabytes per request, several
        // requests overlapped, and the app died with an OutOfMemoryError while merely opening the
        // notifications tab. Follows therefore get their own small page. When it comes back full
        // there may be older follows that were not fetched, so anything older than its oldest
        // entry is held back too: the next page starts from there instead of skipping the gap.
        //
        // The same holds the other way round: when the content page comes back full, a follow
        // older than its oldest entry has to wait too. Otherwise one months-old follow became the
        // page's oldest row, the next page's cursor started below it, and everything in between
        // was never fetched. The page therefore covers only the span both requests fully cover.
        val followsSaturated = followEvents.size >= FOLLOW_LIST_PAGE_LIMIT
        val contentSaturated = mainEvents.size >= limit
        val cutoff = listOfNotNull(
            followEvents.takeIf { followsSaturated }?.minOf { it.createdAt },
            mainEvents.takeIf { contentSaturated }?.minOf { it.createdAt },
        ).maxOrNull()
        val events = (mainEvents + followEvents).filter { cutoff == null || it.createdAt >= cutoff }
        val pageIsFull = contentSaturated || followsSaturated
        return PageEvents(events = events, isFull = pageIsFull)
    }

    private class PageEvents(val events: List<NostrEvent>, val isFull: Boolean)

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
        val zapRequest = if (type == NotificationType.YOUR_POST_WAS_ZAPPED) {
            extractZapRequestOrNull() ?: return null
        } else {
            null
        }
        val actionUserId = zapRequest?.pubKey ?: pubKey
        if (actionUserId == userId) return null

        val amount = if (type == NotificationType.YOUR_POST_WAS_ZAPPED) {
            // A standard NIP-57 receipt carries the paid amount in its BOLT11 invoice. The
            // numeric `amount` tag normally lives in the embedded kind-9734 request and is
            // expressed in millisats. Looking only on the receipt made almost every relay-only
            // notification lose its amount before it ever reached the UI.
            tags.findFirstBolt11()
                ?.let(LnInvoiceUtils::getAmountInSatsOrNull)
                ?.toLong()
                ?.takeIf { it > 0L }
                ?: zapRequest?.tags
                    ?.findFirstZapAmount()
                    ?.toLongOrNull()
                    ?.div(MILLISATS_PER_SAT)
                    ?.takeIf { it > 0L }
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
        var metadata: List<NostrEvent> = emptyList()
        try {
            metadata = query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = wanted,
                    limit = wanted.size,
                ),
            ).latestMetadataByPubkey()
        } finally {
            // Also on cancellation: see LocalEventCache.releaseMetadataPubkeys.
            withContext(NonCancellable) {
                cache?.releaseMetadataPubkeys(wanted - metadata.map { it.pubKey }.toSet())
            }
        }
        return metadata
    }

    private companion object {
        const val MILLISATS_PER_SAT = 1000L
        const val SECONDS_PER_DAY = 86_400L
        const val FOLLOW_ID_PREFIX = "follow:"

        /** Kind 3 events are huge, see the comment where this is used. */
        const val FOLLOW_LIST_PAGE_LIMIT = 20

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
