package net.primal.android.networking.relays

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.db.UsersDatabase
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.domain.mapToRelayDO
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayEventSubscriber
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignResult
import net.primal.domain.nostr.relay.RelayFilter

@Singleton
class RelaysSocketManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val nostrSocketClientFactory: NostrSocketClientFactory,
    private val activeAccountStore: ActiveAccountStore,
    private val usersDatabase: UsersDatabase,
    private val nostrNotary: NostrNotary,
) : RelayEventSubscriber {

    private val scope = CoroutineScope(dispatchers.io())
    private val relayPoolsMutex = Mutex()

    private var relaysObserverJob: Job? = null

    private fun buildRelayPool(signAuthEvent: (suspend (String, String) -> NostrEvent?)? = null) =
        RelayPool(
            dispatchers = dispatchers,
            nostrSocketClientFactory = nostrSocketClientFactory,
            signAuthEvent = signAuthEvent,
        )

    // Only the account's own relays are worth authenticating to. The fallback pool is relays the
    // user did not choose, and the NWC pool speaks for a wallet connection, not the user's own
    // identity — proving who is asking on either would leak more than either relationship calls for.
    private val userRelaysPool: RelayPool = buildRelayPool(signAuthEvent = ::signAuthChallenge)
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
        try {
            customPool.changeRelays(relays = relays)
            customPool.publishEvent(nostrEvent = nostrEvent)
        } finally {
            customPool.closePool()
        }
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
        // A REQ for a private kind discloses the user's pubkey and what they are reading to every
        // relay that receives it. Those queries stay on the account's own relays whenever the user
        // configured any, instead of also being broadcast to the hardcoded public fallback set.
        val skipFallback = filter.isPrivateScopeFilter() && userRelaysPool.hasRelays()
        return coroutineScope {
            // Query both pools. Account relays are authoritative for private/follow data, while
            // public notes are often replicated only on fallback relays. Returning the first
            // non-empty pool made a single matching event suppress the rest of the network and
            // left the home feed empty after the Primal cache was removed.
            val fallback = async {
                if (skipFallback) {
                    RelayPoolQueryResult()
                } else {
                    withTimeoutOrNull(FALLBACK_QUERY_TIMEOUT_MS) {
                        fallbackRelaysPool.query(filter)
                    } ?: RelayPoolQueryResult()
                }
            }
            val userResult = async {
                userRelaysPool
                    .takeIf { it.hasRelays() }
                    ?.let { pool ->
                        // RelayPool itself waits up to eight seconds for EOSE; bound that wait.
                        withTimeoutOrNull(USER_QUERY_TIMEOUT_MS) { pool.query(filter) }
                    }
                    ?: RelayPoolQueryResult()
            }

            val account = userResult.await()
            val public = fallback.await()
            RelayPoolQueryResult(
                events = (account.events + public.events).distinctBy { it.id },
                eoseRelays = account.eoseRelays + public.eoseRelays,
                failedRelays = account.failedRelays + public.failedRelays,
                duplicateCount = account.duplicateCount + public.duplicateCount,
            )
        }
    }

    override suspend fun query(filter: RelayFilter): List<NostrEvent> {
        return queryEvents(filter.toJsonObject()).events
    }

    override fun subscribe(filter: RelayFilter): Flow<NostrEvent> {
        val pool = userRelaysPool.takeIf { it.hasRelays() } ?: fallbackRelaysPool
        return pool.subscribe(filter.toJsonObject())
    }

    fun lastQueryStats() = userRelaysPool.lastQueryStats.value ?: fallbackRelaysPool.lastQueryStats.value

    fun activeSubscriptionCount(): Int =
        userRelaysPool.activeSubscriptionCount() +
            nwcRelaysPool.activeSubscriptionCount() +
            fallbackRelaysPool.activeSubscriptionCount()

    /**
     * Signs a relay's NIP-42 challenge as the active account.
     *
     * The event kind is already in the app's Amber permission set — declared for exactly this —
     * so for the common case this returns synchronously with no prompt: Amber answers a
     * pre-approved kind over its content provider without ever opening. A read-only account, or an
     * external signer that has not granted the permission, cannot produce a signature at all; both
     * fail here the same way a relay would if it simply never authenticates, no worse than today.
     */
    private suspend fun signAuthChallenge(challenge: String, relayUrl: String): NostrEvent? {
        val userId = activeAccountStore.activeUserId()
        if (userId.isEmpty()) return null
        val unsigned = NostrUnsignedEvent(
            pubKey = userId,
            kind = NostrEventKind.ClientAuthentication.value,
            tags = listOf(
                buildJsonArray {
                    add(JsonPrimitive("relay"))
                    add(JsonPrimitive(relayUrl))
                },
                buildJsonArray {
                    add(JsonPrimitive("challenge"))
                    add(JsonPrimitive(challenge))
                },
            ),
            content = "",
        )
        return when (val result = nostrNotary.signNostrEvent(unsigned)) {
            is SignResult.Signed -> result.event
            is SignResult.Rejected -> null
        }
    }

    private fun JsonObject.isPrivateScopeFilter(): Boolean {
        val kinds = (this["kinds"] as? JsonArray) ?: return false
        return kinds
            .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            .any { kind -> kind in PRIVATE_SCOPE_KINDS }
    }

    private companion object {
        // Keep the account pool responsive, then give healthy public relays a separate window.
        // These bounds are intentionally distinct so a slow account relay cannot cancel fallback.
        const val USER_QUERY_TIMEOUT_MS = 3_500L
        const val FALLBACK_QUERY_TIMEOUT_MS = 4_000L

        // Kinds where the request itself is sensitive, not just the payload: direct messages and
        // mute lists reveal who the user talks to and who they block.
        val PRIVATE_SCOPE_KINDS = setOf(
            NostrEventKind.EncryptedDirectMessages.value,
            NostrEventKind.MuteList.value,
            NostrEventKind.StreamMuteList.value,
        )
    }
}
