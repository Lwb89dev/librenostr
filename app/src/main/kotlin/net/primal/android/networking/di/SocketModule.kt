package net.primal.android.networking.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.core.networking.factory.PrimalApiClientFactory
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.domain.global.PrimalServerType
import net.primal.domain.nostr.relay.RelayEventQuerier

@Module
@InstallIn(SingletonComponent::class)
object SocketModule {

    @Provides
    @Singleton
    @PrimalCacheApiClient
    fun providesPrimalApiClient() = PrimalApiClientFactory.getDefault(PrimalServerType.Caching)

    @Provides
    @Singleton
    @PrimalUploadApiClient
    fun providesPrimalUploadClient() = PrimalApiClientFactory.getDefault(PrimalServerType.Upload)

    @Provides
    @Singleton
    @PrimalWalletApiClient
    fun providesPrimalWalletClient() = PrimalApiClientFactory.getDefault(PrimalServerType.Wallet)

    @Singleton
    @Provides
    fun providesNostrSocketClientFactory() = NostrSocketClientFactory

    @Provides
    @Singleton
    fun providesRelayEventQuerier(relaysSocketManager: RelaysSocketManager): RelayEventQuerier =
        relaysSocketManager
}
