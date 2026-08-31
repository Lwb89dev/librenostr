package net.primal.android.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.primal.android.networking.di.PrimalCacheApiClient
import net.primal.android.nostr.notary.NostrNotary
import net.primal.core.caching.MediaCacher
import net.primal.core.networking.primal.PrimalApiClient
import net.primal.data.repository.factory.PrimalRepositoryFactory
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
import net.primal.domain.nostr.cryptography.NostrEventSignatureHandler
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayEventSubscriber
import net.primal.domain.nostr.zaps.NostrZapperFactory
import net.primal.domain.notifications.NotificationRepository
import net.primal.domain.polls.PollsRepository
import net.primal.domain.posts.FeedRepository
import net.primal.domain.premium.PremiumBroadcastRepository
import net.primal.domain.profile.Nip05VerificationService
import net.primal.domain.profile.ProfileRepository
import net.primal.domain.publisher.PrimalPublisher
import net.primal.domain.reads.ArticleRepository
import net.primal.domain.reads.HighlightRepository
import net.primal.domain.streams.StreamRepository
import net.primal.domain.streams.chat.LiveStreamChatRepository
import net.primal.domain.user.UserDataCleanupRepository

@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
object CachingRepositoriesModule {

    @Provides
    fun providesArticleRepository(
        mediaCacher: MediaCacher?,
        relayEventQuerier: RelayEventQuerier,
    ): ArticleRepository {
        return PrimalRepositoryFactory.createArticleRepository(
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )
    }

    @Provides
    fun providesArticleHighlightsRepository(
        primalPublisher: PrimalPublisher,
    ): HighlightRepository {
        return PrimalRepositoryFactory.createArticleHighlightsRepository(
            primalPublisher = primalPublisher,
        )
    }

    @Provides
    fun providesCachingImporterRepository(): CachingImportRepository {
        return PrimalRepositoryFactory.createCachingImportRepository()
    }

    @Provides
    fun providePremiumBroadcastRepository(): PremiumBroadcastRepository =
        PrimalRepositoryFactory.createPremiumBroadcastRepository()

    @Provides
    fun provideChatRepository(
        @PrimalCacheApiClient primalApiClient: PrimalApiClient,
        messageCipher: MessageCipher,
        primalPublisher: PrimalPublisher,
        relayEventQuerier: RelayEventQuerier,
        mediaCacher: MediaCacher?,
    ): ChatRepository =
        PrimalRepositoryFactory.createChatRepository(
            cachingPrimalApiClient = primalApiClient,
            messageCipher = messageCipher,
            primalPublisher = primalPublisher,
            relayEventQuerier = relayEventQuerier,
            mediaCacher = mediaCacher,
        )

    @Provides
    fun provideFeedRepository(
        @PrimalCacheApiClient primalApiClient: PrimalApiClient,
        mediaCacher: MediaCacher?,
        relayEventQuerier: RelayEventQuerier,
    ): FeedRepository =
        PrimalRepositoryFactory.createFeedRepository(
            cachingPrimalApiClient = primalApiClient,
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun provideFeedsRepository(
        relayEventQuerier: RelayEventQuerier,
    ): FeedsRepository =
        PrimalRepositoryFactory.createFeedsRepository(
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun provideEventRepository(
        relayEventQuerier: RelayEventQuerier,
    ): EventRepository =
        PrimalRepositoryFactory.createEventRepository(
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun provideEventUriRepository(): EventUriRepository =
        PrimalRepositoryFactory.createEventUriRepository()

    @Provides
    fun provideEventInteractionRepository(
        primalPublisher: PrimalPublisher,
        nostrZapperFactory: NostrZapperFactory,
    ): EventInteractionRepository =
        PrimalRepositoryFactory.createEventInteractionRepository(
            primalPublisher = primalPublisher,
            nostrZapperFactory = nostrZapperFactory,
        )

    @Provides
    fun provideExploreRepository(
        mediaCacher: MediaCacher?,
        relayEventQuerier: RelayEventQuerier,
    ): ExploreRepository =
        PrimalRepositoryFactory.createExploreRepository(
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    @Singleton
    fun provideNip05VerificationService(): Nip05VerificationService =
        PrimalRepositoryFactory.createNip05VerificationService()

    @Provides
    fun provideProfileRepository(
        primalPublisher: PrimalPublisher,
        nip05VerificationService: Nip05VerificationService,
        relayEventQuerier: RelayEventQuerier,
    ): ProfileRepository =
        PrimalRepositoryFactory.createProfileRepository(
            primalPublisher = primalPublisher,
            nip05VerificationService = nip05VerificationService,
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun provideMutedItemRepository(
        primalPublisher: PrimalPublisher,
        relayEventQuerier: RelayEventQuerier,
    ): MutedItemRepository =
        PrimalRepositoryFactory.createMutedItemRepository(
            primalPublisher = primalPublisher,
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun provideNotificationRepository(
        @PrimalCacheApiClient primalApiClient: PrimalApiClient,
        mediaCacher: MediaCacher?,
        relayEventQuerier: RelayEventQuerier,
    ): NotificationRepository =
        PrimalRepositoryFactory.createNotificationRepository(
            cachingPrimalApiClient = primalApiClient,
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun providesPublicBookmarksRepository(
        @PrimalCacheApiClient primalApiClient: PrimalApiClient,
        primalPublisher: PrimalPublisher,
        relayEventQuerier: RelayEventQuerier,
    ): PublicBookmarksRepository =
        PrimalRepositoryFactory.createPublicBookmarksRepository(
            cachingPrimalApiClient = primalApiClient,
            primalPublisher,
            relayEventQuerier = relayEventQuerier,
        )

    @Provides
    fun providesEventRelayHintsRepository(): EventRelayHintsRepository =
        PrimalRepositoryFactory.createEventRelayHintsRepository()

    @Provides
    fun provideUserDataCleanupRepository(): UserDataCleanupRepository =
        PrimalRepositoryFactory.createUserDataCleanupRepository()

    @Provides
    fun provideStreamRepository(
        primalPublisher: PrimalPublisher,
        nip05VerificationService: Nip05VerificationService,
        relayEventSubscriber: RelayEventSubscriber,
    ): StreamRepository =
        PrimalRepositoryFactory.createStreamRepository(
            primalPublisher = primalPublisher,
            nip05VerificationService = nip05VerificationService,
            relayEventSubscriber = relayEventSubscriber,
        )

    @Provides
    fun provideLiveStreamChatRepository(primalPublisher: PrimalPublisher): LiveStreamChatRepository =
        PrimalRepositoryFactory.createStreamChatRepository(
            primalPublisher = primalPublisher,
        )

    @Provides
    fun providePollsRepository(
        @PrimalCacheApiClient primalApiClient: PrimalApiClient,
        primalPublisher: PrimalPublisher,
        mediaCacher: MediaCacher?,
        relayEventQuerier: RelayEventQuerier,
    ): PollsRepository =
        PrimalRepositoryFactory.createPollsRepository(
            cachingPrimalApiClient = primalApiClient,
            primalPublisher = primalPublisher,
            mediaCacher = mediaCacher,
            relayEventQuerier = relayEventQuerier,
        )
}
