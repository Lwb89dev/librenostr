package net.primal.data.repository.notifications

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.notifications.Notification as NotificationPO
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.notifications.NotificationsApi
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.mappers.local.asNotificationDO
import net.primal.data.repository.notifications.paging.NotificationsRemoteMediator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.notifications.Notification as NotificationDO
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationRepository

@OptIn(ExperimentalPagingApi::class)
class NotificationRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val notificationsApi: NotificationsApi,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : NotificationRepository {

    /** Session-scoped, so the session-start sync does not re-request what a page just fetched. */
    private val localEventCache = LocalEventCache(database = database)

    override fun observeUnseenNotifications(ownerId: String, group: NotificationGroup): Flow<List<NotificationDO>> =
        database.notifications().unseenByGroup(ownerId = ownerId, groupKey = group.name)
            .map { it.map { it.asNotificationDO() } }

    override suspend fun markAllNotificationsAsSeen(authorization: NostrEvent) {
        withContext(dispatcherProvider.io()) {
            val seenAt = Clock.System.now()
            val userId = authorization.pubKey
            database.notifications().markAllUnseenNotificationsAsSeen(
                ownerId = userId,
                seenAt = seenAt.epochSeconds,
            )
        }
    }

    override suspend fun markAllNotificationsAsSeenLocally(userId: String) {
        withContext(dispatcherProvider.io()) {
            database.notifications().markAllUnseenNotificationsAsSeen(
                ownerId = userId,
                seenAt = Clock.System.now().epochSeconds,
            )
        }
    }

    override suspend fun syncNotifications(userId: String, backfillPages: Int) =
        withContext(dispatcherProvider.io()) {
            val querier = relayEventQuerier ?: return@withContext
            val fetcher = RelayNotificationsFetcher(querier, localEventCache)

            // Only the ALL group is walked. Every other tab is a filter over the same events, so
            // fetching per group would ask the relays for the same page several times over.
            var until: Long? = null
            repeat(backfillPages + 1) { page ->
                val result = runCatching {
                    fetcher.fetch(
                        userId = userId,
                        group = NotificationGroup.ALL,
                        limit = SYNC_PAGE_SIZE,
                        until = until,
                    )
                }.getOrNull() ?: return@withContext

                if (result.notifications.isEmpty()) return@withContext
                result.persist(userId = userId, group = NotificationGroup.ALL, database = database)

                // Older than the oldest row of this page, so the next request cannot return it
                // again and stall the walk.
                val oldest = result.notifications.minOf { it.createdAt }
                if (result.relayEventCount < SYNC_PAGE_SIZE) return@withContext
                until = oldest - 1
                Napier.d("Notification backfill page ${page + 1}: ${result.notifications.size} rows")
            }
        }

    override fun observeSeenNotifications(userId: String, group: NotificationGroup): Flow<PagingData<NotificationDO>> {
        return createPager(userId = userId, group = group) {
            database.notifications().seenByGroupPaged(ownerId = userId, groupKey = group.name)
        }.flow.map { it.map { it.asNotificationDO() } }
            .flowOn(dispatcherProvider.io())
    }

    private fun constructRemoteMediator(userId: String, group: NotificationGroup) =
        NotificationsRemoteMediator(
            userId = userId,
            group = group,
            dispatcherProvider = dispatcherProvider,
            notificationsApi = notificationsApi,
            database = database,
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )

    private fun createPager(
        userId: String,
        group: NotificationGroup,
        pagingSourceFactory: () -> PagingSource<Int, NotificationPO>,
    ) = Pager(
        config = PagingConfig(
            pageSize = 50,
            prefetchDistance = 100,
            initialLoadSize = 200,
            enablePlaceholders = true,
        ),
        remoteMediator = constructRemoteMediator(userId = userId, group = group),
        pagingSourceFactory = pagingSourceFactory,
    )

    private companion object {
        /** Matches the paging mediator's page, so a sync and a scroll ask for the same shape. */
        const val SYNC_PAGE_SIZE = 200
    }
}
