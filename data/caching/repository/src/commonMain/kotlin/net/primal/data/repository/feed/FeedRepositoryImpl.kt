package net.primal.data.repository.feed

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import kotlin.time.Clock
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.caching.MediaCacher
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPost as FeedPostPO
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.queries.ChronologicalFeedWithRepostsQueryBuilder
import net.primal.data.local.queries.ExploreFeedQueryBuilder
import net.primal.data.local.queries.FeedQueryBuilder
import net.primal.data.remote.api.feed.FeedApi
import net.primal.data.remote.api.feed.model.MultiKindFeedBySpecRequestBody
import net.primal.data.remote.api.feed.model.MultiKindThreadRequestBody
import net.primal.data.repository.feed.RelayAdvancedSearchFeedFetcher
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.feed.paging.NoteFeedRemoteMediator
import net.primal.data.repository.feed.processors.FeedProcessor
import net.primal.domain.feeds.isFollowSetFeedSpec
import net.primal.domain.feeds.isFollowingNotesFeedSpec
import net.primal.domain.feeds.isUserNotesLwrFeedSpec
import net.primal.domain.feeds.isAdvancedSearchFeedSpec
import net.primal.domain.nostr.relay.RelayEventSubscriber
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.data.repository.feed.processors.persistNoteRepliesAndArticleCommentsToDatabase
import net.primal.data.repository.feed.processors.persistToDatabaseAsTransaction
import net.primal.data.repository.mappers.local.mapAsFeedPostDO
import net.primal.data.repository.mappers.remote.asFeedPageSnapshot
import net.primal.data.repository.utils.cacheAvatarUrls
import net.primal.data.repository.utils.performTopologicalSortOrThis
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.feeds.supportsNoteReposts
import net.primal.domain.posts.FeedPageSnapshot
import net.primal.domain.posts.FeedPost as FeedPostDO
import net.primal.domain.posts.FeedPostRepostInfo
import net.primal.domain.posts.FeedRepository
import net.primal.domain.posts.FeedRepository.Companion.DEFAULT_PAGE_SIZE
import net.primal.shared.data.local.db.withTransaction

internal class FeedRepositoryImpl(
    private val feedApi: FeedApi,
    private val database: CachingDatabase,
    private val dispatcherProvider: DispatcherProvider,
    private val invalidationTracker: FeedSpecInvalidationTracker,
    private val mediaCacher: MediaCacher? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
    /** Shared with every other repository, so the dedupe spans the app and not one object. */
    private val localEventCache: LocalEventCache,
    private val fetchCoordinator: FetchCoordinator,
) : FeedRepository {

    private val notesFeedFetcher = relayEventQuerier?.let {
        RelayNotesFeedFetcher(querier = it, coordinator = fetchCoordinator)
    }

    override fun feedBySpec(
        userId: String,
        feedSpec: String,
        kinds: List<Int>,
        allowMutedThreads: Boolean,
    ): Flow<PagingData<FeedPostDO>> {
        return createPager(userId = userId, feedSpec = feedSpec, kinds = kinds) {
            database.feedPosts().feedQuery(
                query = feedQueryBuilder(
                    userId = userId,
                    feedSpec = feedSpec,
                    allowMutedThreads = allowMutedThreads,
                ).feedQuery(),
            )
        }.flow.map { it.map { feedPostPO -> feedPostPO.mapAsFeedPostDO() } }
            .flowOn(dispatcherProvider.io())
    }

    override suspend fun findNewestPosts(
        userId: String,
        feedDirective: String,
        allowMutedThreads: Boolean,
        limit: Int,
    ) = withContext(dispatcherProvider.io()) {
        database.feedPosts().newestFeedPosts(
            query = feedQueryBuilder(
                userId = userId,
                feedSpec = feedDirective,
                allowMutedThreads = allowMutedThreads,
            ).newestFeedPostsQuery(limit = limit),
        ).map { it.mapAsFeedPostDO() }
    }

    override suspend fun findRepostByPostId(postId: String, userId: String): Result<FeedPostRepostInfo> =
        withContext(dispatcherProvider.io()) {
            database.reposts().findByPostId(postId = postId, authorId = userId)?.let { repostData ->
                Result.success(
                    FeedPostRepostInfo(
                        repostId = repostData.repostId,
                        repostAuthorId = repostData.authorId,
                        repostAuthorDisplayName = null,
                        repostCreatedAt = repostData.createdAt,
                    ),
                )
            } ?: Result.failure(NoSuchElementException("User with given userId didn't repost target post."))
        }

    override suspend fun deleteRepostById(
        postId: String,
        repostId: String,
        userId: String,
    ) = withContext(dispatcherProvider.io()) {
        database.withTransaction {
            database.reposts().deleteById(repostId = repostId)
            database.eventUserStats().reduceEventUserStats(eventId = postId, userId = userId) {
                copy(reposted = false)
            }
            database.eventStats().reduceEventStats(eventId = postId) {
                copy(reposts = (reposts - 1).coerceAtLeast(0))
            }
        }
    }

    override suspend fun deletePostById(postId: String, userId: String) =
        withContext(dispatcherProvider.io()) {
            database.withTransaction {
                val post = database.posts().findAndDeletePostById(postId = postId)
                database.feedsConnections().deletePostConnections(eventId = postId)

                post?.replyToPostId?.let { replyToPostId ->
                    database.eventStats()
                        .reduceEventStats(eventId = replyToPostId) {
                            copy(replies = replies - 1)
                        }
                    database.eventUserStats()
                        .reduceEventUserStats(eventId = replyToPostId, userId = userId) {
                            copy(replied = false)
                        }
                }

                database.eventStats().deleteByEventId(eventId = postId)
                database.eventUserStats().deleteByEventId(eventId = postId)
                database.feedPostsRemoteKeys().deleteAllByEventId(eventId = postId)
            }
            invalidationTracker.invalidateAll()
        }

    override suspend fun findAllPostsByIds(postIds: List<String>): List<FeedPostDO> =
        withContext(dispatcherProvider.io()) {
            database.feedPosts().findAllPostsByIds(postIds).map { it.mapAsFeedPostDO() }
        }

    override suspend fun findPostsById(postId: String): FeedPostDO? =
        withContext(dispatcherProvider.io()) {
            database.feedPosts().findAllPostsByIds(listOf(postId)).firstOrNull()?.mapAsFeedPostDO()
        }

    override suspend fun fetchConversation(
        userId: String,
        noteId: String,
        limit: Int,
        kinds: List<Int>,
    ) {
        withContext(dispatcherProvider.io()) {
            val response = fetchConversationResponse(
                userId = userId,
                noteId = noteId,
                limit = limit,
                kinds = kinds,
            )
            mediaCacher?.cacheAvatarUrls(metadata = response.metadata, cdnResources = response.cdnResources)
            response.persistToDatabaseAsTransaction(userId = userId, database = database)
            response.persistNoteRepliesAndArticleCommentsToDatabase(noteId = noteId, database = database)

            // Relay responses do not contain Primal's synthetic stats payload. Resolve
            // interaction events directly from the configured relays so the note and its
            // replies display current like/reply/repost/zap counters immediately when a
            // thread is opened.
            relayEventQuerier?.let { querier ->
                val eventIds = (response.notes + response.articles + response.reposts)
                    .map { it.id }
                    .distinct()
                if (eventIds.isNotEmpty()) {
                    val stats = RelayEventStatsFetcher(querier).fetch(
                        eventIds = eventIds,
                        userId = userId,
                    )
                    database.withTransaction {
                        database.eventStats().upsertAll(stats.eventStats)
                        if (stats.userStats.isNotEmpty()) {
                            database.eventUserStats().upsertAll(stats.userStats)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchConversationResponse(
        userId: String,
        noteId: String,
        limit: Int,
        kinds: List<Int>,
    ) = relayEventQuerier?.let { querier ->
        RelayThreadFetcher(querier, localEventCache).fetch(noteId = noteId, kinds = kinds, limit = limit)
    } ?: run {
        try {
            feedApi.getMultiKindThread(
                MultiKindThreadRequestBody(
                    eventId = noteId,
                    userPubKey = userId,
                    kinds = kinds,
                    limit = limit,
                ),
            )
        } catch (error: NetworkException) {
            throw NetworkException(message = error.message, cause = error)
        }
    }

    override suspend fun removeFeedSpec(userId: String, feedSpec: String) {
        withContext(dispatcherProvider.io()) {
            database.feedPostsRemoteKeys().deleteByDirective(ownerId = userId, directive = feedSpec)
            database.feedsConnections().deleteConnectionsByDirective(ownerId = userId, feedSpec = feedSpec)
            database.articleFeedsConnections().deleteConnectionsBySpec(ownerId = userId, spec = feedSpec)
        }
        invalidationTracker.invalidate(ownerId = userId, feedSpec = feedSpec)
    }

    override suspend fun replaceFeed(
        userId: String,
        feedSpec: String,
        snapshot: FeedPageSnapshot,
    ) = withContext(dispatcherProvider.io()) {
        FeedProcessor(
            feedSpec = feedSpec,
            database = database,
            invalidationTracker = invalidationTracker,
        ).processAndPersistToDatabase(
            userId = userId,
            snapshot = snapshot,
            clearFeed = true,
        )

        relayEventQuerier?.let { querier ->
            val eventIds = (snapshot.notes + snapshot.articles + snapshot.reposts)
                .map { it.id }
                .distinct()
            if (eventIds.isNotEmpty()) {
                val stats = RelayEventStatsFetcher(querier).fetch(eventIds = eventIds, userId = userId)
                database.withTransaction {
                    database.eventStats().upsertAll(stats.eventStats)
                    if (stats.userStats.isNotEmpty()) {
                        database.eventUserStats().upsertAll(stats.userStats)
                    }
                }
                invalidationTracker.invalidate(ownerId = userId, feedSpec = feedSpec)
            }
        }
        Unit
    }

    override fun streamNewNotes(userId: String, feedSpec: String): Flow<NostrEvent> {
        // The injected querier is the socket manager, which already implements the subscriber
        // port; nothing extra has to be wired to open a live REQ.
        val subscriber = relayEventQuerier as? RelayEventSubscriber ?: return emptyFlow()
        val fetcher = notesFeedFetcher ?: return emptyFlow()

        return flow {
            val authors = fetcher.resolveAuthors(userId = userId, feedSpec = feedSpec)
            if (authors.isEmpty()) return@flow

            // `since` is now, so the subscription carries only what is published from here on;
            // history stays the paging path's job.
            val since = Clock.System.now().epochSeconds
            val chunks = authors.take(MAX_STREAMED_AUTHORS).chunked(STREAM_AUTHOR_CHUNK)
            val streams = chunks.map { chunk ->
                subscriber.subscribe(
                    RelayFilter(
                        kinds = listOf(
                            NostrEventKind.ShortTextNote.value,
                            NostrEventKind.ShortTextNoteRepost.value,
                        ),
                        authors = chunk,
                        since = since,
                    ),
                )
            }
            emitAll(merge(*streams.toTypedArray()))
        }.flowOn(dispatcherProvider.io())
    }

    override suspend fun fetchFeedPageSnapshot(
        userId: String,
        feedSpec: String,
        kinds: List<Int>,
        notes: String?,
        until: Long?,
        since: Long?,
        order: String?,
        limit: Int,
    ): FeedPageSnapshot =
        withContext(dispatcherProvider.io()) {
            val querier = relayEventQuerier
            val response = if (
                querier != null &&
                feedSpec.isAdvancedSearchFeedSpec()
            ) {
                RelayAdvancedSearchFeedFetcher(querier).fetch(
                    userId = userId,
                    feedSpec = feedSpec,
                    fallbackKinds = kinds,
                    limit = limit,
                    until = until,
                    since = since,
                )
            } else if (
                querier != null &&
                (feedSpec.isFollowingNotesFeedSpec() || feedSpec.isFollowSetFeedSpec())
            ) {
                (notesFeedFetcher ?: RelayNotesFeedFetcher(querier = querier, coordinator = fetchCoordinator)).fetch(
                    userId = userId,
                    feedSpec = feedSpec,
                    includeReplies = feedSpec.isUserNotesLwrFeedSpec(),
                    limit = limit,
                    until = until,
                    since = since,
                )
            } else {
                val body = MultiKindFeedBySpecRequestBody(
                    spec = feedSpec,
                    userPubKey = userId,
                    kinds = kinds,
                    notes = notes,
                    until = until,
                    since = since,
                    order = order,
                    limit = limit,
                )
                val cacheResponse = feedApi.getMultiKindFeedBySpec(body = body)
                mediaCacher?.cacheAvatarUrls(
                    metadata = cacheResponse.metadata,
                    cdnResources = cacheResponse.cdnResources,
                )
                cacheResponse
            }
            response.asFeedPageSnapshot()
        }

    override suspend fun findConversation(userId: String, noteId: String): List<FeedPostDO> {
        return observeConversation(userId = userId, noteId = noteId).firstOrNull() ?: emptyList()
    }

    override fun observeConversation(userId: String, noteId: String): Flow<List<FeedPostDO>> {
        return database.threadConversations().observeNoteConversation(
            postId = noteId,
            userId = userId,
        ).map { list ->
            list.map { it.mapAsFeedPostDO() }
                .performTopologicalSortOrThis()
        }
    }

    @OptIn(ExperimentalPagingApi::class)
    private fun createPager(
        userId: String,
        feedSpec: String,
        kinds: List<Int> = FeedRepository.DEFAULT_FEED_KINDS,
        pagingSourceFactory: () -> PagingSource<Int, FeedPostPO>,
    ) = Pager(
        config = PagingConfig(
            pageSize = DEFAULT_PAGE_SIZE,
            // Fetch 50 notes initially, then request 20 more when the user is close to the
            // end of the currently loaded window.
            prefetchDistance = DEFAULT_PAGE_SIZE / 2,
            initialLoadSize = FeedRepository.INITIAL_PAGE_SIZE,
            enablePlaceholders = true,
        ),
        remoteMediator = NoteFeedRemoteMediator(
            fetchCoordinator = fetchCoordinator,
            dispatcherProvider = dispatcherProvider,
            feedSpec = feedSpec,
            userId = userId,
            feedApi = feedApi,
            database = database,
            invalidationTracker = invalidationTracker,
            mediaCacher = mediaCacher,
            kinds = kinds,
            relayEventQuerier = relayEventQuerier,
        ),
        pagingSourceFactory = {
            invalidationTracker.track(
                ownerId = userId,
                feedSpec = feedSpec,
                pagingSource = pagingSourceFactory(),
            )
        },
    )

    private fun feedQueryBuilder(
        userId: String,
        feedSpec: String,
        allowMutedThreads: Boolean,
    ): FeedQueryBuilder =
        when {
            feedSpec.supportsNoteReposts() -> ChronologicalFeedWithRepostsQueryBuilder(
                feedSpec = feedSpec,
                userPubkey = userId,
                allowMutedThreads = allowMutedThreads,
            )

            else -> ExploreFeedQueryBuilder(
                feedSpec = feedSpec,
                userPubkey = userId,
                allowMutedThreads = allowMutedThreads,
        )
    }


    private companion object {
        /** One REQ per chunk; a handful is well within the pool's subscription budget. */
        const val STREAM_AUTHOR_CHUNK = 250

        /** Past this the filter gets unwieldy; the periodic refresh still covers the rest. */
        const val MAX_STREAMED_AUTHORS = 1_000
    }

}
