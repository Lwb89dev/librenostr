package net.primal.data.repository.factory

import net.primal.core.caching.MediaCacher
import net.primal.core.networking.primal.PrimalApiClient
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.factory.PrimalApiServiceFactory
import net.primal.data.repository.UserDataCleanupRepositoryImpl
import net.primal.data.repository.articles.ArticleRepositoryImpl
import net.primal.data.repository.articles.HighlightRepositoryImpl
import net.primal.data.repository.bookmarks.PublicBookmarksRepositoryImpl
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.events.EventInteractionRepositoryImpl
import net.primal.data.repository.events.EventRelayHintsRepositoryImpl
import net.primal.data.repository.events.EventRepositoryImpl
import net.primal.data.repository.events.EventUriRepositoryImpl
import net.primal.data.repository.explore.ExploreRepositoryImpl
import net.primal.data.repository.feed.FeedRepositoryImpl
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.feeds.FeedsRepositoryImpl
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.importer.CachingImportRepositoryImpl
import net.primal.data.repository.messages.ChatRepositoryImpl
import net.primal.data.repository.messages.processors.MessagesProcessor
import net.primal.data.repository.mute.MutedItemRepositoryImpl
import net.primal.data.repository.nip05.Nip05HttpClient
import net.primal.data.repository.nip05.Nip05VerificationServiceImpl
import net.primal.data.repository.notifications.NotificationRepositoryImpl
import net.primal.data.repository.polls.PollsRepositoryImpl
import net.primal.data.repository.profile.ProfileRepositoryImpl
import net.primal.data.repository.streams.LiveStreamChatRepositoryImpl
import net.primal.data.repository.streams.StreamRepositoryImpl
import net.primal.domain.bookmarks.PublicBookmarksRepository
import net.primal.domain.events.EventInteractionRepository
import net.primal.domain.events.EventRelayHintsRepository
import net.primal.domain.events.EventRepository
import net.primal.domain.explore.ExploreRepository
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.links.EventUriRepository
import net.primal.domain.messages.ChatRepository
import net.primal.domain.mutes.MutedItemRepository
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayEventSubscriber
import net.primal.domain.nostr.zaps.NostrZapperFactory
import net.primal.domain.notifications.NotificationRepository
import net.primal.domain.polls.PollsRepository
import net.primal.domain.posts.FeedRepository
import net.primal.domain.profile.Nip05VerificationService
import net.primal.domain.profile.ProfileRepository
import net.primal.domain.publisher.PrimalPublisher
import net.primal.domain.reads.ArticleRepository
import net.primal.domain.reads.HighlightRepository
import net.primal.domain.streams.StreamRepository
import net.primal.domain.streams.chat.LiveStreamChatRepository
import net.primal.domain.user.UserDataCleanupRepository

abstract class CommonRepositoryFactory {

    private val dispatcherProvider = createDispatcherProvider()

    private val feedSpecInvalidationTracker = FeedSpecInvalidationTracker()

    /**
     * One hot layer for the whole app.
     *
     * Lazy because [resolveCachingDatabase] is the subclass's to answer and must not be called
     * while this base class is still being constructed.
     */
    private val localEventCache by lazy { LocalEventCache(database = resolveCachingDatabase()) }

    /**
     * One request coordinator for the whole app.
     *
     * The relay transport is handed to it per call rather than held, so this needs nothing but a
     * dispatcher to exist and cannot be raced into existence twice by two providers starting at
     * the same time.
     */
    private val fetchCoordinator by lazy { FetchCoordinator(dispatcherProvider = dispatcherProvider) }

    abstract fun resolveCachingDatabase(): CachingDatabase

    fun createArticleRepository(
        mediaCacher: MediaCacher? = null,
        relayEventQuerier: RelayEventQuerier,
    ): ArticleRepository {
        return ArticleRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
            fetchCoordinator = fetchCoordinator,
        )
    }

    fun createArticleHighlightsRepository(
        primalPublisher: PrimalPublisher,
    ): HighlightRepository {
        return HighlightRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            primalPublisher = primalPublisher,
        )
    }

    fun createCachingImportRepository(): CachingImportRepository {
        return CachingImportRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            importApi = null,
            broadcastApi = null,
            invalidationTracker = feedSpecInvalidationTracker,
        )
    }

    fun createChatRepository(
        cachingPrimalApiClient: PrimalApiClient,
        messageCipher: MessageCipher,
        primalPublisher: PrimalPublisher,
        relayEventQuerier: RelayEventQuerier? = null,
        mediaCacher: MediaCacher? = null,
    ): ChatRepository {
        return ChatRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            messageCipher = messageCipher,
            messagesApi = PrimalApiServiceFactory.createMessagesApi(
                primalApiClient = cachingPrimalApiClient,
                relayEventQuerier = relayEventQuerier,
            ),
            messagesProcessor = MessagesProcessor(
                database = resolveCachingDatabase(),
                messageCipher = messageCipher,
                mediaCacher = mediaCacher,
                relayEventQuerier = relayEventQuerier,
            ),
            primalPublisher = primalPublisher,
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )
    }

    fun createFeedRepository(
        cachingPrimalApiClient: PrimalApiClient,
        mediaCacher: MediaCacher? = null,
        relayEventQuerier: RelayEventQuerier? = null,
    ): FeedRepository {
        return FeedRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            feedApi = PrimalApiServiceFactory.createFeedApi(cachingPrimalApiClient),
            database = resolveCachingDatabase(),
            invalidationTracker = feedSpecInvalidationTracker,
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
            localEventCache = localEventCache,
            fetchCoordinator = fetchCoordinator,
        )
    }

    fun createFeedsRepository(
        relayEventQuerier: RelayEventQuerier? = null,
    ): FeedsRepository {
        return FeedsRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            relayEventQuerier = relayEventQuerier,
        )
    }

    fun createEventRepository(
        relayEventQuerier: RelayEventQuerier,
    ): EventRepository {
        return EventRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            relayEventQuerier = relayEventQuerier,
        )
    }

    fun createEventUriRepository(): EventUriRepository {
        return EventUriRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
        )
    }

    fun createEventInteractionRepository(
        primalPublisher: PrimalPublisher,
        nostrZapperFactory: NostrZapperFactory,
    ): EventInteractionRepository {
        return EventInteractionRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            primalPublisher = primalPublisher,
            nostrZapperFactory = nostrZapperFactory,
            database = resolveCachingDatabase(),
        )
    }

    fun createEventRelayHintsRepository(): EventRelayHintsRepository {
        return EventRelayHintsRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
        )
    }

    fun createExploreRepository(
        mediaCacher: MediaCacher? = null,
        relayEventQuerier: RelayEventQuerier,
    ): ExploreRepository {
        return ExploreRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )
    }

    fun createMutedItemRepository(
        primalPublisher: PrimalPublisher,
        relayEventQuerier: RelayEventQuerier,
    ): MutedItemRepository {
        return MutedItemRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            primalPublisher = primalPublisher,
            relayEventQuerier = relayEventQuerier,
        )
    }

    fun createNotificationRepository(
        cachingPrimalApiClient: PrimalApiClient,
        mediaCacher: MediaCacher? = null,
        relayEventQuerier: RelayEventQuerier? = null,
    ): NotificationRepository {
        return NotificationRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            notificationsApi = PrimalApiServiceFactory.createNotificationsApi(cachingPrimalApiClient),
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
            localEventCache = localEventCache,
        )
    }

    fun createNip05VerificationService(): Nip05VerificationService {
        return Nip05VerificationServiceImpl(
            nip05HttpClient = Nip05HttpClient.create(),
            verificationDao = resolveCachingDatabase().nip05Verifications(),
        )
    }

    fun createProfileRepository(
        primalPublisher: PrimalPublisher,
        nip05VerificationService: Nip05VerificationService? = null,
        relayEventQuerier: RelayEventQuerier? = null,
    ): ProfileRepository {
        return ProfileRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            primalPublisher = primalPublisher,
            nip05VerificationService = nip05VerificationService,
            relayEventQuerier = relayEventQuerier,
            fetchCoordinator = fetchCoordinator,
        )
    }

    fun createPublicBookmarksRepository(
        cachingPrimalApiClient: PrimalApiClient,
        primalPublisher: PrimalPublisher,
        relayEventQuerier: RelayEventQuerier? = null,
    ): PublicBookmarksRepository {
        return PublicBookmarksRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            primalPublisher = primalPublisher,
            usersApi = PrimalApiServiceFactory.createUsersApi(cachingPrimalApiClient),
            relayEventQuerier = relayEventQuerier,
        )
    }

    fun createUserDataCleanupRepository(): UserDataCleanupRepository {
        return UserDataCleanupRepositoryImpl(
            database = resolveCachingDatabase(),
            invalidationTracker = feedSpecInvalidationTracker,
            localEventCache = localEventCache,
            fetchCoordinator = fetchCoordinator,
        )
    }

    fun createStreamRepository(
        primalPublisher: PrimalPublisher,
        nip05VerificationService: Nip05VerificationService? = null,
        relayEventSubscriber: RelayEventSubscriber,
    ): StreamRepository =
        StreamRepositoryImpl(
            database = resolveCachingDatabase(),
            dispatcherProvider = dispatcherProvider,
            profileRepository = createProfileRepository(
                primalPublisher = primalPublisher,
                nip05VerificationService = nip05VerificationService,
                relayEventQuerier = relayEventSubscriber,
            ),
            liveStreamApi = PrimalApiServiceFactory.createStreamMonitor(relayEventSubscriber),
        )

    fun createStreamChatRepository(primalPublisher: PrimalPublisher): LiveStreamChatRepository {
        return LiveStreamChatRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            database = resolveCachingDatabase(),
            primalPublisher = primalPublisher,
        )
    }

    fun createPollsRepository(
        cachingPrimalApiClient: PrimalApiClient,
        primalPublisher: PrimalPublisher,
        mediaCacher: MediaCacher? = null,
        relayEventQuerier: RelayEventQuerier? = null,
    ): PollsRepository {
        return PollsRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            pollsApi = PrimalApiServiceFactory.createPollsApi(cachingPrimalApiClient),
            primalPublisher = primalPublisher,
            database = resolveCachingDatabase(),
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )
    }
}
