package net.primal.data.repository.notifications.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.aakira.napier.Napier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notifications.Notification
import net.primal.data.local.dao.notifications.NotificationData
import net.primal.data.local.dao.notifications.NotificationGroupCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.remote.api.notifications.NotificationsApi
import net.primal.data.remote.api.notifications.model.NotificationsRequestBody
import net.primal.data.remote.api.notifications.model.wireToken
import net.primal.data.repository.feed.processors.persistToDatabaseAsTransaction
import net.primal.data.repository.mappers.remote.mapNotNullAsNotificationPO
import net.primal.data.repository.mappers.remote.mapNotNullAsProfileStatsPO
import net.primal.data.repository.mappers.remote.mapNotNullAsStreamDataPO
import net.primal.data.repository.notifications.persist
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.notifications.NotificationGroup
import net.primal.shared.data.local.db.withTransaction

@ExperimentalPagingApi
class NotificationsRemoteMediator(
    private val userId: String,
    private val group: NotificationGroup,
    private val dispatcherProvider: DispatcherProvider,
    private val notificationsApi: NotificationsApi,
    private val database: CachingDatabase,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : RemoteMediator<Int, Notification>() {

    private var lastSeenTimestamp: Long = Instant.DISTANT_PAST.epochSeconds

    /** Session-scoped, so referenced notes and actor metadata are not re-requested per page. */
    private val localEventCache = LocalEventCache(database = database)

    private val lastRequests: MutableMap<LoadType, NotificationsRequestBody> = mutableMapOf()

    fun updateLastSeenTimestamp(lastSeen: Instant) {
        lastSeenTimestamp = lastSeen.epochSeconds
    }

    private suspend fun ensureLastSeenTimestamp() {
        if (lastSeenTimestamp == Instant.DISTANT_PAST.epochSeconds) {
            notificationsApi.getLastSeenTimestamp(userId = userId)?.let {
                updateLastSeenTimestamp(lastSeen = it)
            }
        }
    }

    override suspend fun initialize(): InitializeAction {
        // Always refresh once when the screen is opened. Existing caches can contain only the
        // newest notification (especially after migrating away from Primal's cache server), and
        // skipping here leaves the user with an apparently non-scrollable one-item list.
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(loadType: LoadType, state: PagingState<Int, Notification>): MediatorResult {
        relayEventQuerier?.let { return loadFromRelays(loadType = loadType, state = state, querier = it) }
        val timestamp: Long? = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.PREPEND -> {
                state.firstItemOrNull()?.data?.createdAt
                    ?: withContext(dispatcherProvider.io()) {
                        database.notifications().firstByGroup(ownerId = userId, groupKey = group.name)?.createdAt
                    }
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
            }

            LoadType.APPEND -> {
                state.lastItemOrNull()?.data?.createdAt
                    ?: withContext(dispatcherProvider.io()) {
                        database.notifications().lastByGroup(ownerId = userId, groupKey = group.name)?.createdAt
                    }
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        if (timestamp == null && loadType != LoadType.REFRESH) {
            return MediatorResult.Error(IllegalStateException("Remote key not found."))
        }

        val initialRequestBody = NotificationsRequestBody(
            pubkey = userId,
            userPubkey = userId,
            // Notifications are a single chronological stream. Request a generous first page so
            // the cache-less client does not appear to contain only the newest event.
            limit = maxOf(state.config.pageSize, 200),
            typeGroup = group.wireToken,
        )

        val requestBody = when (loadType) {
            LoadType.REFRESH -> initialRequestBody
            LoadType.PREPEND -> initialRequestBody.copy(
                since = timestamp,
                until = Clock.System.now().epochSeconds,
            )

            LoadType.APPEND -> initialRequestBody.copy(until = timestamp)
        }

        if (lastRequests[loadType] == requestBody) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }

        val response = try {
            withContext(dispatcherProvider.io()) {
                ensureLastSeenTimestamp()
                notificationsApi.getNotifications(body = requestBody)
            }
        } catch (error: NetworkException) {
            Napier.w(error) { "Failed to get notifications." }
            return MediatorResult.Error(error)
        }

        mediaCacher?.cacheAvatarUrls(metadata = response.metadata, cdnResources = response.cdnResources)
        lastRequests[loadType] = requestBody

        val streamData = response.liveActivity.mapNotNullAsStreamDataPO()
        val userProfileStats = response.primalUserProfileStats.mapNotNullAsProfileStatsPO()
        val notifications = response.primalNotifications.mapNotNullAsNotificationPO()

        withContext(dispatcherProvider.io()) {
            FeedResponse(
                paging = null,
                metadata = response.metadata,
                notes = response.notes,
                articles = emptyList(),
                reposts = emptyList(),
                zaps = emptyList(),
                referencedEvents = response.primalReferencedNotes,
                primalEventStats = response.primalNoteStats,
                primalEventUserStats = emptyList(),
                cdnResources = response.cdnResources,
                primalLinkPreviews = response.primalLinkPreviews,
                primalRelayHints = response.primalRelayHints,
                primalUserNames = response.primalUserNames,
                primalLegendProfiles = response.primalLegendProfiles,
                primalPremiumInfo = response.primalPremiumInfo,
                blossomServers = response.blossomServers,
                polls = response.polls,
                pollResponses = response.pollResponses,
                primalPollStats = response.primalPollStats,
            ).persistToDatabaseAsTransaction(
                userId = userId,
                database = database,
            )

            val tagged = notifications.mapWithSeenAtTimestamps()

            database.withTransaction {
                database.profileStats().upsertAll(data = userProfileStats)
                database.notifications().upsertAll(data = tagged)
                database.notificationGroupCrossRef().insertAll(
                    refs = tagged.map {
                        NotificationGroupCrossRef(
                            notificationId = it.notificationId,
                            ownerId = userId,
                            groupKey = group.name,
                        )
                    },
                )
                database.streams().upsertStreamData(data = streamData)
            }
        }

        return MediatorResult.Success(endOfPaginationReached = false)
    }

    private suspend fun loadFromRelays(
        loadType: LoadType,
        state: PagingState<Int, Notification>,
        querier: RelayEventQuerier,
    ): MediatorResult {
        if (loadType == LoadType.PREPEND) return MediatorResult.Success(endOfPaginationReached = true)
        val until = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.APPEND -> state.lastItemOrNull()?.data?.createdAt
        }
        val result = try {
            withContext(dispatcherProvider.io()) {
                net.primal.data.repository.notifications.RelayNotificationsFetcher(querier, localEventCache).fetch(
                    userId = userId,
                    group = group,
                    limit = maxOf(state.config.pageSize, RELAY_PAGE_SIZE),
                    until = until,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Napier.w(error) { "Failed to get notifications from relays." }
            return MediatorResult.Error(error)
        }
        if (result.notifications.isEmpty()) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        withContext(dispatcherProvider.io()) {
            result.persist(userId = userId, group = group, database = database)
        }
        // End of list is decided by what the relays returned, not by how many rows survived the
        // group filter. Judging by the filtered count stopped a sparse tab — Zaps especially —
        // after its first page even when older events were still available.
        return MediatorResult.Success(endOfPaginationReached = result.relayEventCount < RELAY_PAGE_SIZE)
    }

    private fun List<NotificationData>.mapWithSeenAtTimestamps(): List<NotificationData> {
        return this.map {
            val seenAt = if (it.createdAt <= lastSeenTimestamp) lastSeenTimestamp else null
            it.copy(seenGloballyAt = seenAt)
        }
    }

    private companion object {
        const val RELAY_PAGE_SIZE = 200
    }
}
