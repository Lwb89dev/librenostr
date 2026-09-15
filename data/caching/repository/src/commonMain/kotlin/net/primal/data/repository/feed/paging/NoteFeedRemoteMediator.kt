package net.primal.data.repository.feed.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.aakira.napier.Napier
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import net.primal.core.caching.MediaCacher
import net.primal.core.networking.sockets.errors.NostrNoticeException
import net.primal.core.networking.utils.retryNetworkCall
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPost
import net.primal.data.local.dao.notes.FeedPostRemoteKey
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.feed.FeedApi
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.remote.api.feed.model.MultiKindFeedBySpecRequestBody
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.feed.RelayAdvancedSearchFeedFetcher
import net.primal.data.repository.feed.RelayEventStatsFetcher
import net.primal.data.repository.feed.RelayNotesFeedFetcher
import net.primal.data.repository.feed.processors.FeedProcessor
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.feeds.isAdvancedSearchFeedSpec
import net.primal.domain.feeds.isNotesBookmarkFeedSpec
import net.primal.domain.feeds.isProfileAuthoredNoteRepliesFeedSpec
import net.primal.domain.feeds.isProfileAuthoredNotesFeedSpec
import net.primal.domain.feeds.isRelayServableNotesFeedSpec
import net.primal.domain.feeds.isUserNotesLwrFeedSpec
import net.primal.domain.feeds.supportsUpwardsNotesPagination
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.posts.FeedRepository
import net.primal.shared.data.local.db.withTransaction

@ExperimentalPagingApi
@OptIn(ExperimentalAtomicApi::class)
internal class NoteFeedRemoteMediator(
    private val dispatcherProvider: DispatcherProvider,
    private val feedSpec: String,
    private val userId: String,
    private val feedApi: FeedApi,
    private val database: CachingDatabase,
    private val invalidationTracker: FeedSpecInvalidationTracker,
    private val mediaCacher: MediaCacher? = null,
    private val kinds: List<Int> = FeedRepository.DEFAULT_FEED_KINDS,
    private val relayEventQuerier: RelayEventQuerier? = null,
    private val fetchCoordinator: FetchCoordinator,
    private val localEventCache: LocalEventCache,
) : RemoteMediator<Int, FeedPost>() {

    private val relayFeedFetcher = relayEventQuerier?.let {
        RelayNotesFeedFetcher(querier = it, coordinator = fetchCoordinator, cache = localEventCache)
    }
    private val relayAdvancedSearchFetcher = relayEventQuerier?.let {
        RelayAdvancedSearchFeedFetcher(querier = it, coordinator = fetchCoordinator)
    }
    private val relayEventStatsFetcher = relayEventQuerier?.let {
        RelayEventStatsFetcher(querier = it, coordinator = fetchCoordinator)
    }
    private val useRelayAdvancedSearch = relayAdvancedSearchFetcher != null && feedSpec.isAdvancedSearchFeedSpec()
    private val useRelayFollowingFeed = relayFeedFetcher != null && feedSpec.isRelayServableNotesFeedSpec()

    private val lastRequests: MutableMap<LoadType, Pair<MultiKindFeedBySpecRequestBody, Long>> = mutableMapOf()

    // Together these make a single empty APPEND batch non-fatal instead of permanently ending
    // pagination. FeedProcessor.processRemoteKeys() only writes a FeedPostRemoteKey for events
    // actually returned, so an empty batch leaves findLastFeedPostRemoteKey() resolving the same
    // stale key next time — without this, that identical request trips RepeatingRequestBodyException
    // and this PagingSource generation's APPEND direction is done forever (Paging3's retry() only
    // re-attempts a load in LoadState.Error, never one that already completed as NotLoading with
    // endOfPaginationReached=true — there is no lower-level API to undo that short of a fresh
    // PagingSource generation).

    /** Furthest `until` any APPEND attempt has already queried, regardless of whether it
     * returned events. Lets the next attempt step strictly further back instead of repeating a
     * window the DB-derived cursor hasn't moved past. Reset only on REFRESH. */
    private val furthestAppendUntil = AtomicReference<Long?>(null)

    /** Consecutive APPEND batches that returned zero notes/polls/reposts. Reset by any batch
     * with at least one event, by REFRESH, and by a manual retry. Bounds automatic step-back
     * retrying: see MAX_CONSECUTIVE_EMPTY_APPEND_BATCHES. */
    private val consecutiveEmptyAppendBatches = AtomicReference(0)

    /** One-shot APPEND page-size override consumed by exactly the next APPEND fetch, set by a
     * manual "load more" retry so a single tap reliably asks for MANUAL_APPEND_RETRY_LIMIT notes
     * instead of the normal small steady-state page size. */
    private val forceNextAppendLimit = AtomicReference<Int?>(null)

    init {
        // Lets a UI action (NoteFeedViewModel -> FeedRepository.retryAppendFeed) reach back into
        // this specific mediator instance without FeedRepositoryImpl needing to hold a reference
        // to it — mirrors how PagingSources register themselves via invalidationTracker.track().
        invalidationTracker.registerAppendRetryHandler(ownerId = userId, feedSpec = feedSpec) {
            consecutiveEmptyAppendBatches.store(0)
            forceNextAppendLimit.store(MANUAL_APPEND_RETRY_LIMIT)
            // furthestAppendUntil is deliberately left as-is: resetting it would make the next
            // attempt recompute the same stale boundary that was just exhausted, defeating the
            // whole point of a "load further back" retry.
        }
    }

    private val feedProcessor: FeedProcessor = FeedProcessor(
        feedSpec = feedSpec,
        database = database,
        invalidationTracker = invalidationTracker,
    )

    private suspend fun String.isLastCacheTimestampOlderThan(duration: Duration): Boolean {
        val lastCachedAt = withContext(dispatcherProvider.io()) {
            database.feedPostsRemoteKeys()
                .lastCachedAt(ownerId = userId, directive = this@isLastCacheTimestampOlderThan)
        } ?: return true

        return lastCachedAt < Clock.System.now().minus(duration).epochSeconds
    }

    private suspend fun shouldResetLocalCache() =
        when {
            // A search is a new relay query every time it is opened. Never
            // reuse an older local page for the same text, otherwise results
            // can appear stale even though the relay fetch completed.
            feedSpec.isAdvancedSearchFeedSpec() -> true
            feedSpec.isNotesBookmarkFeedSpec() -> true
            feedSpec.isProfileAuthoredNotesFeedSpec() -> true
            feedSpec.isProfileAuthoredNoteRepliesFeedSpec() -> true
            feedSpec.supportsUpwardsNotesPagination() -> feedSpec.isLastCacheTimestampOlderThan(duration = 24.hours)
            else -> feedSpec.isLastCacheTimestampOlderThan(duration = 3.minutes)
        }

    override suspend fun initialize(): InitializeAction {
        return when {
            shouldResetLocalCache() -> {
                clearFeedSpec(feedSpec = feedSpec)
                InitializeAction.LAUNCH_INITIAL_REFRESH
            }

            else -> {
                InitializeAction.SKIP_INITIAL_REFRESH
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun load(loadType: LoadType, state: PagingState<Int, FeedPost>): MediatorResult {
        Napier.i("feed_spec $feedSpec load called ($loadType)")
        if (loadType == LoadType.PREPEND) {
            Napier.w("feed_spec $feedSpec load exit 9")
            return MediatorResult.Success(endOfPaginationReached = true)
        }

        return try {
            val remoteKey = when (loadType) {
//                LoadType.PREPEND -> findFirstFeedPostRemoteKey(state = state)
                LoadType.APPEND -> findLastFeedPostRemoteKey(state = state)
                else -> null
            }

            if (remoteKey == null && loadType != LoadType.REFRESH) {
                throw RemoteKeyNotFoundException()
            }

            withContext(dispatcherProvider.io()) {
                syncFeed(
                    loadType = loadType,
                    pagingState = state,
                    remoteKey = remoteKey,
                )
            }

            Napier.i("feed_spec $feedSpec load exit 6")
            // Only APPEND can end deliberately here now: a bounded run of consecutive empty
            // batches (see syncAppend), not the accidental one-shot give-up a single flaky empty
            // batch used to cause via RepeatingRequestBodyException below.
            val appendExhausted = loadType == LoadType.APPEND &&
                consecutiveEmptyAppendBatches.load() >= MAX_CONSECUTIVE_EMPTY_APPEND_BATCHES
            MediatorResult.Success(endOfPaginationReached = appendExhausted)
        } catch (error: IOException) {
            Napier.w("feed_spec $feedSpec load exit 7", error)
            MediatorResult.Error(error)
        } catch (error: NostrNoticeException) {
            Napier.w("feed_spec $feedSpec load exit 8", error)
            MediatorResult.Error(error)
        } catch (error: NoSuchFeedPostException) {
            Napier.w("feed_spec $feedSpec load exit 2", error)
            MediatorResult.Success(endOfPaginationReached = loadType == LoadType.REFRESH)
        } catch (error: RemoteKeyNotFoundException) {
            Napier.w("feed_spec $feedSpec load exit 3", error)
            MediatorResult.Success(endOfPaginationReached = false)
        } catch (error: NetworkException) {
            Napier.w("feed_spec $feedSpec load exit 5", error)
            MediatorResult.Error(error)
        } catch (error: RepeatingRequestBodyException) {
            Napier.i("feed_spec $feedSpec load exit 4", error)
            MediatorResult.Success(endOfPaginationReached = true)
        }
    }

    private suspend fun clearFeedSpec(feedSpec: String) {
        withContext(dispatcherProvider.io()) {
            database.feedPostsRemoteKeys().deleteByDirective(ownerId = userId, directive = feedSpec)
            database.feedsConnections().deleteConnectionsByDirective(ownerId = userId, feedSpec = feedSpec)
        }
        invalidationTracker.invalidate(ownerId = userId, feedSpec = feedSpec)
    }

    private suspend fun NoteFeedRemoteMediator.syncFeed(
        loadType: LoadType,
        pagingState: PagingState<Int, FeedPost>,
        remoteKey: FeedPostRemoteKey?,
    ) {
        if (loadType == LoadType.REFRESH) {
            // A fresh top-of-feed view must not inherit a give-up/step-back state left over from
            // a prior deep-scroll session: without this, a REFRESH's newly-written (recent,
            // large-timestamp) remote key could compare as "hasn't advanced past" a much older
            // watermark, wrongly forcing the very first post-refresh APPEND to step back from
            // stale history instead of trying the natural next window.
            furthestAppendUntil.store(null)
            consecutiveEmptyAppendBatches.store(0)
        }

        // Load a deliberately larger first snapshot, then keep subsequent pages small to
        // avoid retaining/downloading an unnecessarily large feed at startup.
        val pageSize = if (loadType == LoadType.REFRESH) {
            pagingState.config.initialLoadSize
        } else {
            pagingState.config.pageSize
        }
        val (request, response) = when (loadType) {
            LoadType.REFRESH -> syncRefresh(pageSize = pageSize)
            LoadType.PREPEND -> syncPrepend(remoteKey = remoteKey, pageSize = pageSize)
            LoadType.APPEND -> syncAppend(remoteKey = remoteKey, pageSize = pageSize)
        }

        feedProcessor.processAndPersistToDatabase(
            userId = userId,
            response = response,
            clearFeed = loadType == LoadType.REFRESH,
        )
        // Only REFRESH's page is guaranteed to be what the user is actually looking at the
        // moment it lands; APPEND pages are prefetched ahead of scroll (Paging3's own
        // prefetchDistance) and may not be seen for a while, if ever. Fetching stats for those
        // eagerly was most of the relay round-trips a refresh didn't need yet — APPEND pages now
        // get their stats lazily, from the viewport-visibility trigger in NoteFeedViewModel /
        // NoteFeedList instead (EventRepository.fetchAndCacheEventStats), once a note actually
        // scrolls into view.
        if (loadType == LoadType.REFRESH) {
            refreshRelayEventStats(response = response)
        }

        lastRequests[loadType] = request to Clock.System.now().epochSeconds
    }

    private suspend fun refreshRelayEventStats(response: FeedResponse) {
        val statsFetcher = relayEventStatsFetcher ?: return
        val eventIds = (response.notes + response.articles + response.reposts).map { it.id }.distinct()
        if (eventIds.isEmpty()) return
        val stats = statsFetcher.fetch(eventIds = eventIds, userId = userId)
        database.withTransaction {
            database.eventStats().upsertAll(stats.eventStats)
            if (stats.userStats.isNotEmpty()) {
                database.eventUserStats().upsertAll(stats.userStats)
            }
        }
        // EventStats is a relation and is intentionally excluded from Room's paging
        // observed-entity set, so an explicit invalidate is the only way their counters redraw
        // without navigating away/back. Safe to do unconditionally now that this function only
        // ever runs for REFRESH: REFRESH clears and replaces the whole feed spec, so there is no
        // already-scrolled window for an invalidate to disrupt (that risk — and why APPEND used
        // to skip this — is why APPEND no longer calls this function at all, see syncFeed above).
        invalidationTracker.invalidate(ownerId = userId, feedSpec = feedSpec)
    }

    private suspend fun syncRefresh(pageSize: Int): Pair<MultiKindFeedBySpecRequestBody, FeedResponse> {
        val requestBody = MultiKindFeedBySpecRequestBody(
            spec = feedSpec,
            userPubKey = userId,
            kinds = kinds,
            limit = pageSize,
        )
        val response = fetchFeedPage(requestBody)
        return requestBody to response
    }

    private suspend fun syncPrepend(
        remoteKey: FeedPostRemoteKey?,
        pageSize: Int,
    ): Pair<MultiKindFeedBySpecRequestBody, FeedResponse> {
        val requestBody = MultiKindFeedBySpecRequestBody(
            spec = feedSpec,
            userPubKey = userId,
            kinds = kinds,
            limit = pageSize,
            since = remoteKey?.untilId,
            order = "asc",
        )

        lastRequests[LoadType.PREPEND]?.let { (lastRequest, lastRequestAt) ->
            if (lastRequest == requestBody && lastRequestAt.isRequestCacheExpired()) {
                throw RepeatingRequestBodyException()
            }
        }

        val feedResponse = fetchFeedPage(requestBody)
        return requestBody to feedResponse
    }

    private suspend fun syncAppend(
        remoteKey: FeedPostRemoteKey?,
        pageSize: Int,
    ): Pair<MultiKindFeedBySpecRequestBody, FeedResponse> {
        // An empty batch writes no FeedPostRemoteKey (see FeedProcessor.processRemoteKeys), so
        // candidateUntil can resolve to the exact same boundary a previous attempt already
        // queried. When it hasn't advanced past the furthest point already probed, step back by
        // EMPTY_BATCH_STEP_BACK instead of repeating that window — that repeat is exactly what
        // used to trip RepeatingRequestBodyException below on nothing more than one flaky empty
        // relay round trip.
        val candidateUntil = remoteKey?.sinceId
        val watermark = furthestAppendUntil.load()
        val until = if (candidateUntil != null && watermark != null && candidateUntil >= watermark) {
            watermark - EMPTY_BATCH_STEP_BACK
        } else {
            candidateUntil
        }

        val effectiveLimit = forceNextAppendLimit.load() ?: pageSize
        forceNextAppendLimit.store(null)

        val requestBody = MultiKindFeedBySpecRequestBody(
            spec = feedSpec,
            userPubKey = userId,
            kinds = kinds,
            limit = effectiveLimit,
            until = until,
        )

        lastRequests[LoadType.APPEND]?.let { (lastRequest, lastRequestAt) ->
            if (lastRequest == requestBody && lastRequestAt.isRequestCacheExpired()) {
                throw RepeatingRequestBodyException()
            }
        }

        val feedResponse = fetchFeedPage(requestBody)

        if (until != null) {
            furthestAppendUntil.update { current -> minOf(until, current ?: until) }
        }
        val returnedCount = feedResponse.notes.size + feedResponse.polls.size + feedResponse.reposts.size
        consecutiveEmptyAppendBatches.store(if (returnedCount == 0) consecutiveEmptyAppendBatches.load() + 1 else 0)

        return requestBody to feedResponse
    }

    private suspend fun fetchFeedPage(requestBody: MultiKindFeedBySpecRequestBody): FeedResponse {
        val advancedSearchFetcher = relayAdvancedSearchFetcher
        if (useRelayAdvancedSearch && advancedSearchFetcher != null) {
            return advancedSearchFetcher.fetch(
                userId = userId,
                feedSpec = feedSpec,
                fallbackKinds = kinds,
                limit = requestBody.limit ?: FeedRepository.DEFAULT_PAGE_SIZE,
                until = requestBody.until,
                since = requestBody.since,
            )
        }
        val fetcher = relayFeedFetcher
        if (useRelayFollowingFeed && fetcher != null) {
            return fetcher.fetch(
                userId = userId,
                feedSpec = feedSpec,
                includeReplies = feedSpec.isUserNotesLwrFeedSpec() ||
                    feedSpec.isProfileAuthoredNoteRepliesFeedSpec(),
                limit = requestBody.limit ?: FeedRepository.DEFAULT_PAGE_SIZE,
                until = requestBody.until,
                since = requestBody.since,
            )
        }
        return retryNetworkCall(
            onBeforeDelay = { error -> Napier.w("Attempting FeedRemoteMediator.retry().", error) },
        ) {
            val response = withContext(dispatcherProvider.io()) {
                feedApi.getMultiKindFeedBySpec(body = requestBody)
            }
            response.paging ?: throw NetworkException("PagingEvent not found.")
            mediaCacher?.cacheAvatarUrls(metadata = response.metadata, cdnResources = response.cdnResources)
            response
        }
    }

    private fun Long.isRequestCacheExpired() = (Clock.System.now().epochSeconds - this) < LAST_REQUEST_EXPIRY

//    private suspend fun findFirstFeedPostRemoteKey(state: PagingState<Int, FeedPost>): FeedPostRemoteKey? {
//        val firstItem = state.firstItemOrNull()
//            ?: newestFeedPostInDatabaseOrNull()
//            ?: throw NoSuchFeedPostException()
//
//        return withContext(dispatcherProvider.io()) {
//            Napier.i(
//                "feed_spec $feedDirective looking for firstItem postId=${firstItem.data.postId}" +
//                    " and repostId=${firstItem.data.repostId}",
//            )
//            database.feedPostsRemoteKeys().find(
//                postId = firstItem.data.postId,
//                repostId = firstItem.data.repostId,
//                directive = feedDirective,
//            )
//        }
//    }

    private suspend fun findLastFeedPostRemoteKey(state: PagingState<Int, FeedPost>): FeedPostRemoteKey? {
        val (lastItemId, lastItemRepostId) =
            state.lastItemOrNull()?.let {
                (state.lastItemOrNull()?.data?.postId to state.lastItemOrNull()?.data?.repostId)
            } ?: oldestFeedPostInDatabaseOrNull()
                ?: throw NoSuchFeedPostException()

        return withContext(dispatcherProvider.io()) {
            Napier.i(
                "feed_spec $feedSpec looking for lastItem postId=$lastItemId" +
                    " and repostId=$lastItemRepostId",
            )
            database.feedPostsRemoteKeys().find(
                ownerId = userId,
                postId = lastItemId,
                repostId = lastItemRepostId,
                directive = feedSpec,
            )
        }
    }

    private suspend fun oldestFeedPostInDatabaseOrNull() =
        withContext(dispatcherProvider.io()) {
            database.feedsConnections().findLastBySpec(ownerId = userId, spec = feedSpec)?.let { it.eventId to null }
        }

    private class NoSuchFeedPostException : RuntimeException()

    private class RepeatingRequestBodyException : RuntimeException()

    private class RemoteKeyNotFoundException : RuntimeException()

    companion object {
        private val LAST_REQUEST_EXPIRY = 10.seconds.inWholeSeconds

        /** How far back (seconds) a stuck APPEND cursor is forced past its last attempted
         * boundary. Trade-off: too small and a genuinely sparse account (long real gaps between
         * posts) burns through MAX_CONSECUTIVE_EMPTY_APPEND_BATCHES probing tiny slices, giving
         * up while relays still have more; too large and a single step can leap over real content
         * sitting between two sparse points — the cursor only ever moves backward, nothing
         * revisits a skipped span. 6h is small next to plausible gaps in an actively-followed
         * multi-author feed while coarse enough that a handful of steps covers a meaningful
         * stretch before giving up. Not derived from telemetry (none exists for this path); tune
         * if real-world gaps prove different. */
        private val EMPTY_BATCH_STEP_BACK = 6.hours.inWholeSeconds

        /** Consecutive empty APPEND batches allowed before deliberately giving up. 1 alone
         * absorbs the reported failure mode (a single flaky/timed-out relay round trip)
         * trivially; 4 total attempts (3 forced step-backs of EMPTY_BATCH_STEP_BACK each, up to
         * ~18h of history probed) bounds automatic retrying for a genuine gap or truly-exhausted
         * history to a small, fixed number of extra round trips rather than unthrottled
         * hammering, then hands off to the user via the "load more" retry. */
        private const val MAX_CONSECUTIVE_EMPTY_APPEND_BATCHES = 4

        /** One-shot APPEND limit for the fetch immediately following a manual "load more" tap,
         * so a single tap reliably asks for ~100 older notes rather than the steady-state page
         * size. `limit` is an unvalidated pass-through all the way to the relay/cache-server
         * request body (same as `until`/`since`), and 50 is already used as the initial page
         * size elsewhere in this same class, so 100 is well within already-demonstrated-safe
         * range. */
        private const val MANUAL_APPEND_RETRY_LIMIT = 100
    }
}
