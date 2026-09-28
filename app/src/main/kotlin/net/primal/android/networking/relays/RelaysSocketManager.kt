package net.primal.android.networking.relays

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
import net.primal.core.networking.tor.RouteController
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayEventSubscriber
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignResult
import net.primal.domain.nostr.relay.RelayFilter

@Singleton
@Suppress("TooManyFunctions")
class RelaysSocketManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val nostrSocketClientFactory: NostrSocketClientFactory,
    private val activeAccountStore: ActiveAccountStore,
    private val usersDatabase: UsersDatabase,
    private val nostrNotary: NostrNotary,
    private val routeController: RouteController,
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
    // user did not choose — proving who is asking there would leak more than that relationship
    // calls for.
    private val userRelaysPool: RelayPool = buildRelayPool(signAuthEvent = ::signAuthChallenge)
    private val fallbackRelaysPool: RelayPool = buildRelayPool()

    val userRelayPoolStatus = userRelaysPool.relayPoolStatus
    val fallbackRelayPoolStatus = fallbackRelaysPool.relayPoolStatus

    init {
        initFallbackRelaysPool()
        observeActiveUserId()
        observeNetworkRoute()
    }

    /**
     * Reconnects every relay when the network mode changes.
     *
     * The transport already closes every socket opened the old way (see `RouteController`), so no
     * relay can keep talking over the route the user just turned off. This makes the pools react
     * deliberately rather than by noticing a dropped connection: sockets are closed cleanly, the
     * status shown in the relay list is reset, and the relays come back at once by the new route
     * instead of on their next use.
     */
    private fun observeNetworkRoute() =
        scope.launch {
            routeController.state
                .map { it.epoch }
                .distinctUntilChanged()
                .drop(1)
                .collect { resetAllConnections() }
        }

    private suspend fun resetAllConnections() {
        relayPoolsMutex.withLock {
            userRelaysPool.resetConnections()
            fallbackRelaysPool.resetConnections()
        }
        connectPool(userRelaysPool)
        connectPool(fallbackRelaysPool)
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
                updateRelayPools(regularRelays = userRelays)
            }
        }

    // The two sources merged into userRelaysPool: the account's own configured relays (from the
    // DB, editable in Settings > Manage Relays) and a bounded set of the follow list's most
    // common NIP-65 write relays, added on top by OutboxRelayCoordinator so queries reach relays
    // that actually carry a given author's content, not just the ones the user chose to connect
    // to. Kept as two separate fields rather than merging at the write site so either source can
    // update independently without the other's most recent value being lost.
    private var latestConfiguredUserRelays: List<Relay> = emptyList()
    private var latestOutboxEnrichmentRelays: List<Relay> = emptyList()

    private suspend fun updateRelayPools(regularRelays: List<Relay>?) {
        relayPoolsMutex.withLock {
            latestConfiguredUserRelays = regularRelays.orEmpty()
            applyRelayPoolChangeLocked()
        }
    }

    /**
     * Adds [relays] to the query pool only — never written to `usersDatabase.relays()`, so they
     * never appear in Settings > Manage Relays as if the user had added them. Callers must mark
     * every relay `write = false`: this set exists to widen where the pool *reads* from, never to
     * add a publish target the user didn't choose.
     */
    suspend fun updateOutboxEnrichmentRelays(relays: List<Relay>) {
        relayPoolsMutex.withLock {
            latestOutboxEnrichmentRelays = relays
            applyRelayPoolChangeLocked()
        }
    }

    /** Call under [relayPoolsMutex]. */
    private fun applyRelayPoolChangeLocked() {
        val configuredUrls = latestConfiguredUserRelays.map { it.url }.toSet()
        // Enrichment relays are appended after the user's own, never prepended, so RelayPool's
        // own MAX_RELAYS cap never evicts a relay the user actually chose in favor of one this
        // class added on its behalf.
        val merged = latestConfiguredUserRelays +
            latestOutboxEnrichmentRelays
                .filterNot { it.url in configuredUrls }
                .map { it.copy(read = true, write = false) }
        if (userRelaysPool.relays != merged) {
            userRelaysPool.changeRelays(relays = merged)
            connectPool(userRelaysPool)
        }
    }

    private suspend fun clearRelayPools() =
        relayPoolsMutex.withLock {
            latestOutboxEnrichmentRelays = emptyList()
            userRelaysPool.closePool()
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
        val customPool = buildRelayPool(signAuthEvent = ::signAuthChallenge)
        try {
            customPool.changeRelays(relays = relays)
            customPool.relays.forEach { customPool.tryConnectingToRelay(it.url) }
            customPool.publishEvent(nostrEvent = nostrEvent)
        } finally {
            customPool.destroy()
        }
    }

    /** Queries only the supplied relays; used for NIP-17 inboxes without a public fallback. */
    suspend fun queryEvents(filter: RelayFilter, relays: List<Relay>): RelayPoolQueryResult {
        val customPool = buildRelayPool(signAuthEvent = ::signAuthChallenge)
        return try {
            customPool.changeRelays(relays)
            customPool.relays.forEach { customPool.tryConnectingToRelay(it.url) }
            customPool.query(filter.toJsonObject())
        } finally {
            customPool.destroy()
        }
    }

    /** Keeps a private REQ on exactly the supplied NIP-17 relays for the collector's lifetime. */
    fun subscribeEvents(filter: RelayFilter, relays: List<Relay>): Flow<NostrEvent> = flow {
        val customPool = buildRelayPool(signAuthEvent = ::signAuthChallenge)
        try {
            customPool.changeRelays(relays)
            customPool.relays.forEach { customPool.tryConnectingToRelay(it.url) }
            emitAll(customPool.subscribe(filter.toJsonObject()))
        } finally {
            customPool.destroy()
        }
    }

    fun configuredUserRelays(userId: String): List<Relay> =
        usersDatabase.relays().findRelays(userId = userId, kind = RelayKind.UserRelay).map { it.mapToRelayDO() }

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
            val userHasRelays = userRelaysPool.hasRelays()
            // Measured on-device: the account relays answer in about half a second, while the
            // public pool — the same REQ, fanned out to more sockets, behind its own 4-slot gate —
            // routinely sat queued long enough to hit its 4s cap. The cap then cancelled it and
            // threw away everything it had collected, so every sequential stage of a feed or
            // notifications load cost a flat 4s for nothing. The account pool is now the answer;
            // the public pool only supplements it for a short grace after that answer lands, and
            // keeps whatever it collected by then instead of losing it.
            val settleFallback = CompletableDeferred<Unit>()
            val giveUpOnFallback = CompletableDeferred<Unit>()
            // A hard cap on the public pool measured from right now — time spent queued for one
            // of its gate permits included. Without it, a query whose account-relay answer was
            // empty waited on the public pool with no bound at all: measured at up to 17s, which
            // stalled the session-start notification sync on a single missing quoted note.
            val fallbackCap = launch {
                delay(FALLBACK_QUERY_TIMEOUT_MS)
                settleFallback.complete(Unit)
                delay(FALLBACK_SETTLE_MARGIN_MS)
                giveUpOnFallback.complete(Unit)
            }
            val fallback = async {
                if (skipFallback) {
                    RelayPoolQueryResult()
                } else {
                    fallbackRelaysPool.query(
                        filter = filter,
                        timeoutMs = FALLBACK_QUERY_TIMEOUT_MS,
                        settleSignal = settleFallback,
                    )
                }
            }
            val account = if (userHasRelays) {
                // The pool's own deadline returns a partial result; this outer bound only guards
                // against a query stuck waiting for a gate permit.
                withTimeoutOrNull(RelayPool.SUBSCRIBE_TIMEOUT.toLong()) {
                    userRelaysPool.query(filter = filter, timeoutMs = USER_QUERY_TIMEOUT_MS)
                } ?: RelayPoolQueryResult()
            } else {
                RelayPoolQueryResult()
            }

            // Only cut the public pool short early when the account relays actually produced
            // something: content they do not hold at all still gets the public pool's full cap,
            // and an account with no relays configured relies on it entirely.
            val graceTimer = if (account.events.isNotEmpty()) {
                launch {
                    delay(FALLBACK_GRACE_AFTER_USER_MS)
                    settleFallback.complete(Unit)
                    delay(FALLBACK_SETTLE_MARGIN_MS)
                    giveUpOnFallback.complete(Unit)
                }
            } else {
                null
            }
            // Settling makes a running public query hand back what it has; one still queued for a
            // gate permit by then has collected nothing, so it is dropped instead of waited for.
            val public = select {
                fallback.onAwait { it }
                giveUpOnFallback.onAwait { null }
            } ?: RelayPoolQueryResult().also { fallback.cancel() }
            // coroutineScope waits for its children: the timers must not keep this call open.
            fallbackCap.cancel()
            graceTimer?.cancel()
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

        /**
         * How long the public pool may keep adding events after the account relays have answered.
         * The two start together, so by then it has already had as long as the account relays
         * took (about half a second, measured) plus this.
         */
        const val FALLBACK_GRACE_AFTER_USER_MS = 500L

        /** Time for a settled public query to hand back what it collected. */
        const val FALLBACK_SETTLE_MARGIN_MS = 300L

        // Kinds where the request itself is sensitive, not just the payload: direct messages and
        // mute lists reveal who the user talks to and who they block.
        val PRIVATE_SCOPE_KINDS = setOf(
            NostrEventKind.EncryptedDirectMessages.value,
            NostrEventKind.GiftWrap.value,
            NostrEventKind.MuteList.value,
            NostrEventKind.StreamMuteList.value,
        )
    }
}
