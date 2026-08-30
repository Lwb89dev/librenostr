package net.primal.android.networking.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.core.networking.primal.PrimalApiClient
import net.primal.core.networking.primal.PrimalCacheFilter
import net.primal.core.networking.primal.PrimalServerConnectionStatus
import net.primal.core.networking.primal.PrimalQueryResult
import net.primal.core.networking.primal.PrimalSubscriptionBufferedResult
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.global.PrimalServerType
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayEventSubscriber

@Module
@InstallIn(SingletonComponent::class)
object SocketModule {

    @Provides
    @Singleton
    @PrimalCacheApiClient
    fun providesPrimalApiClient(): PrimalApiClient = RelayOnlyApiClient

    @Provides
    @Singleton
    @PrimalWalletApiClient
    fun providesPrimalWalletClient(): PrimalApiClient = RelayOnlyApiClient

    @Singleton
    @Provides
    fun providesNostrSocketClientFactory() = NostrSocketClientFactory

    @Provides
    @Singleton
    fun providesRelayEventQuerier(relaysSocketManager: RelaysSocketManager): RelayEventQuerier =
        relaysSocketManager

    @Provides
    @Singleton
    fun providesRelayEventSubscriber(relaysSocketManager: RelaysSocketManager): RelayEventSubscriber =
        relaysSocketManager
}

/** Compatibility implementation for legacy constructors; it never opens a socket. */
private object RelayOnlyApiClient : PrimalApiClient {
    private val status = MutableStateFlow(
        PrimalServerConnectionStatus(serverType = PrimalServerType.Caching, connected = false),
    )

    override val connectionStatus: StateFlow<PrimalServerConnectionStatus> = status

    override suspend fun query(message: PrimalCacheFilter): PrimalQueryResult = unavailable()

    override suspend fun subscribe(
        subscriptionId: String,
        message: PrimalCacheFilter,
    ): Flow<NostrIncomingMessage> = unavailable()

    override suspend fun subscribeBufferedOnInactivity(
        subscriptionId: String,
        message: PrimalCacheFilter,
        inactivityTimeout: Duration,
    ): Flow<PrimalSubscriptionBufferedResult> = unavailable()

    override suspend fun subscribeBuffered(
        subscriptionId: String,
        message: PrimalCacheFilter,
    ): Flow<PrimalSubscriptionBufferedResult> = unavailable()

    override suspend fun closeSubscription(subscriptionId: String): Boolean = false

    private fun <T> unavailable(): T = throw NetworkException(
        "Centralized API is disabled; use the relay data path.",
    )
}
