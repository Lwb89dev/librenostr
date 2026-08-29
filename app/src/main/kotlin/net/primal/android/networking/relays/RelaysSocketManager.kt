package net.primal.android.networking.relays

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.db.UsersDatabase
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.domain.mapToRelayDO
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

@Singleton
class RelaysSocketManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val nostrSocketClientFactory: NostrSocketClientFactory,
    private val activeAccountStore: ActiveAccountStore,
    private val usersDatabase: UsersDatabase,
) : RelayEventQuerier {

    private val scope = CoroutineScope(dispatchers.io())
    private val relayPoolsMutex = Mutex()

    private var relaysObserverJob: Job? = null

    private fun buildRelayPool() =
        RelayPool(
            dispatchers = dispatchers,
            nostrSocketClientFactory = nostrSocketClientFactory,
        )

    private val userRelaysPool: RelayPool = buildRelayPool()
    private val nwcRelaysPool: RelayPool = buildRelayPool()
    private val fallbackRelaysPool: RelayPool = buildRelayPool()

    val userRelayPoolStatus = userRelaysPool.relayPoolStatus
    val fallbackRelayPoolStatus = fallbackRelaysPool.relayPoolStatus

    init {
        initFallbackRelaysPool()
        observeActiveUserId()
    }

    private fun initFallbackRelaysPool() {
        fallbackRelaysPool.changeRelays(FALLBACK_RELAYS)
        connectPool(fallbackRelaysPool)
    }

    private fun connectPool(pool: RelayPool) {
        pool.relays.forEach { relay ->
            scope.launch { pool.tryConnectingToRelay(relay.url) }
        }
    }

    private fun observeActiveUserId() =
        scope.launch {
            activeAccountStore.activeUserId.collect { userId ->
                when {
                    userId.isEmpty() -> {
                        relaysObserverJob?.cancel()
                        relaysObserverJob = null
                        clearRelayPools()
                    }

                    else -> {
                        relaysObserverJob?.cancel()
                        relaysObserverJob = observeRelays(userId)
                    }
                }
            }
        }

    private fun observeRelays(userId: String): Job =
        scope.launch {
            usersDatabase.relays().observeRelays(userId = userId).collect { relays ->
                val userRelays = relays.filter { it.kind == RelayKind.UserRelay }.map { it.mapToRelayDO() }
                val nwcRelays = relays.filter { it.kind == RelayKind.NwcRelay }.map { it.mapToRelayDO() }
                updateRelayPools(regularRelays = userRelays, walletRelays = nwcRelays)
            }
        }

    private suspend fun updateRelayPools(regularRelays: List<Relay>?, walletRelays: List<Relay>?) {
        relayPoolsMutex.withLock {
            val sanitizedUserRelays = regularRelays.orEmpty()
            val userRelaysChanged = userRelaysPool.relays != sanitizedUserRelays
            if (userRelaysChanged) {
                userRelaysPool.changeRelays(relays = sanitizedUserRelays)
                connectPool(userRelaysPool)
            }

            val sanitizedWalletRelays = walletRelays.orEmpty()
            val nwcRelaysChanged = nwcRelaysPool.relays != sanitizedWalletRelays
            if (nwcRelaysChanged) {
                nwcRelaysPool.changeRelays(relays = sanitizedWalletRelays)
            }
        }
    }

    private suspend fun clearRelayPools() =
        relayPoolsMutex.withLock {
            userRelaysPool.closePool()
            nwcRelaysPool.closePool()
        }

    @Throws(NostrPublishException::class)
    suspend fun publishEvent(nostrEvent: NostrEvent) {
        if (userRelaysPool.hasRelays()) {
            userRelaysPool.publishEvent(nostrEvent = nostrEvent)
        } else {
            fallbackRelaysPool.publishEvent(nostrEvent = nostrEvent)
        }
    }

    @Throws(NostrPublishException::class)
    suspend fun publishEvent(nostrEvent: NostrEvent, relays: List<Relay>) {
        val customPool = buildRelayPool()
        customPool.changeRelays(relays = relays)
        customPool.publishEvent(nostrEvent = nostrEvent)
        customPool.closePool()
    }

    @Throws(NostrPublishException::class)
    suspend fun publishNwcEvent(nostrEvent: NostrEvent) {
        if (!nwcRelaysPool.hasRelays()) {
            throw NostrPublishException(cause = IllegalStateException("nwc relay not found"))
        }

        nwcRelaysPool.publishEvent(nostrEvent = nostrEvent)
    }

    fun tryConnectingToAllUserRelays() {
        userRelaysPool.relays.forEach {
            scope.launch {
                userRelaysPool.tryConnectingToRelay(it.url)
            }
        }
    }

    suspend fun tryConnectingToUserRelay(url: String) = userRelaysPool.tryConnectingToRelay(url)

    suspend fun queryEvents(filter: JsonObject): RelayPoolQueryResult {
        val userResult = userRelaysPool
            .takeIf { it.hasRelays() }
            ?.query(filter)
            ?.takeIf { it.events.isNotEmpty() }
        return userResult ?: fallbackRelaysPool.query(filter)
    }

    override suspend fun query(filter: RelayFilter): List<NostrEvent> {
        return queryEvents(filter.toJsonObject()).events
    }

    fun lastQueryStats() = userRelaysPool.lastQueryStats.value ?: fallbackRelaysPool.lastQueryStats.value

    fun activeSubscriptionCount(): Int =
        userRelaysPool.activeSubscriptionCount() +
            nwcRelaysPool.activeSubscriptionCount() +
            fallbackRelaysPool.activeSubscriptionCount()
}
