package net.primal.android.networking.relays

import androidx.annotation.VisibleForTesting
import io.github.aakira.napier.Napier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.cleanWebSocketUrl
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.NostrSocketClient
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.networking.sockets.SocketConnectionClosedCallback
import net.primal.core.networking.sockets.SocketConnectionOpenedCallback
import net.primal.core.networking.sockets.filterBySubscriptionId
import net.primal.core.networking.sockets.publishEventAndAwaitResponse
import net.primal.core.networking.sockets.subscription
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.serialization.toNostrJsonObject

class RelayPool(
    dispatchers: DispatcherProvider,
    private val nostrSocketClientFactory: NostrSocketClientFactory,
    /**
     * Answers a relay's NIP-42 challenge with a signed kind-22242 event, or null to decline.
     *
     * Left null for pools that must not authenticate as the user: the fallback pool is relays the
     * user did not choose, and proving identity to one of those on every query would tell it who
     * is asking. Only the user's own relay pool is wired with a signer.
     */
    private val signAuthEvent: (suspend (challenge: String, relayUrl: String) -> NostrEvent?)? = null,
) {

    companion object {
        const val PUBLISH_TIMEOUT = 10_000
        const val SUBSCRIBE_TIMEOUT = 8_000
        const val FIRST_EOSE_GRACE_MS = 400L
        const val MIN_QUERY_TIMEOUT_MS = 500L
        const val MAX_EVENTS_PER_QUERY = 500
        const val MAX_RELAYS = 30
        const val MAX_ACTIVE_SUBSCRIPTIONS = 64

        /**
         * A single [query] call sends REQ to *every* connected relay at once, but nothing
         * coordinated how many [query] calls could be doing that simultaneously: a feed
         * pull-to-refresh alone fans out into a page-body fetch (its own 8-wide semaphore),
         * a referenced-notes lookup (unbounded), a profile-metadata fetch (a second, separate
         * 8-wide semaphore), and — once that page lands — an event-stats fetch that queries 4
         * interaction kinds in parallel with no limit at all, and Paging3 kicks off the next
         * page's APPEND before the REFRESH above has even finished. None of those share any
         * state, so in the worst case 15+ concurrent [query] calls each broadcast a REQ to the
         * same handful of relay sockets within milliseconds of each other, and a single feed page
         * measured on-device needed ~60-125 [query] calls in total.
         *
         * A rejection (nostr.mom and offchain.pub, confirmed via captured device logs, reject a
         * socket's REQ outright once too many land on it close together) now fails that relay leg
         * in milliseconds instead of riding out [SUBSCRIBE_TIMEOUT] (see
         * `RelayRejectedRequestException` in [queryOneRelay]). A second, independent bug used to
         * compound this: every relay's subscription stayed open until the *whole* query() call
         * settled across all 7 relays, not just until that relay's own EOSE — so a relay that
         * answered in well under a second (confirmed via an isolated probe: offchain.pub does)
         * still counted as open for up to [SUBSCRIBE_TIMEOUT] whenever some other relay in the
         * same call was slower. [queryOneRelay] now closes each relay's subscription the moment
         * *it* answers, which the same probe confirmed removes the rejections outright on its own
         * (20 "too many concurrent REQs" down to 0, identical request pattern against a fresh
         * connection).
         *
         * With both of those fixed, raising this cap past 4 was re-tried twice more, expecting the
         * removed risk to make a wider cap pay off — both times it measured slower instead (12.2s/
         * 24.3s at 4 vs. 17.5s/29.6s and 17.8s/33.9s at 8, first-stage/full-cycle, same feed), with
         * "too many concurrent REQs" reappearing despite the fixes above. On the relays this
         * account actually uses, sending more *new* REQs into the same instant seems to matter on
         * its own, independent of how quickly they then close. 4 is the empirically faster value,
         * confirmed across four separate on-device measurements, not a theoretical one — re-measure
         * on-device (a cold-start run and a warm one both, since cache state changes how many calls
         * a page needs and confounds a same-session before/after comparison) before changing it.
         */
        const val MAX_CONCURRENT_QUERIES = 4

        /** Amber answers a pre-approved kind synchronously; this only guards against no answer. */
        const val AUTH_SIGN_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(dispatchers.io() + SupervisorJob())
    private val activeSubscriptions = AtomicInteger(0)
    private val queryGate = Semaphore(MAX_CONCURRENT_QUERIES)

    @VisibleForTesting
    var subscriptionIdFactory: () -> String = { Uuid.random().toString() }

    private val _lastQueryStats = MutableStateFlow<RelayQueryStats?>(null)
    val lastQueryStats = _lastQueryStats.asStateFlow()

    fun activeSubscriptionCount(): Int = activeSubscriptions.get()

    @VisibleForTesting
    var relays: List<Relay> = emptyList()

    @VisibleForTesting
    var socketClients = listOf<NostrSocketClient>()

    private val _relayPoolStatus = MutableStateFlow(mapOf<String, Boolean>())
    val relayPoolStatus = _relayPoolStatus.asStateFlow()
    private fun updateRelayStatus(url: String, connected: Boolean) =
        scope.launch {
            _relayPoolStatus.getAndUpdate {
                it.toMutableMap().apply { this[url] = connected }
            }
        }

    private val onSocketConnectionOpenedCallback: SocketConnectionOpenedCallback = { url ->
        updateRelayStatus(url = url, connected = true)
    }

    private val onSocketConnectionClosedCallback: SocketConnectionClosedCallback = { url, _ ->
        updateRelayStatus(url = url, connected = false)
    }

    fun changeRelays(relays: List<Relay>) {
        val sanitized = relays
            .filter { it.url.isValidRelayUrl() }
            .map { it.copy(url = it.url.normalizedRelayUrl()) }
            .distinctBy { it.url }
            .take(MAX_RELAYS)
        val existingRelayUrls = socketClients.map { it.socketUrl.normalizedRelayUrl() }
        val newRelayUrls = sanitized.map { it.url }

        val toAddRelayUrls = newRelayUrls.filter { it !in existingRelayUrls }
        val toAddSocketClients = sanitized.filter { it.url in toAddRelayUrls }.mapAsNostrSocketClient()
        val toRemoveSocketClients = socketClients.filter { it.socketUrl.normalizedRelayUrl() !in newRelayUrls }

        val newSocketClients = socketClients.toMutableList().apply {
            removeAll(toRemoveSocketClients)
            addAll(toAddSocketClients)
        }

        socketClients = newSocketClients
        toRemoveSocketClients.forEach { client ->
            updateRelayStatus(url = client.socketUrl, connected = false)
            scope.launch { client.close() }
        }
        this.relays = sanitized
    }

    fun closePool() {
        socketClients.forEach { client ->
            updateRelayStatus(url = client.socketUrl, connected = false)
            scope.launch { client.close() }
        }
        socketClients = emptyList()
        relays = emptyList()
    }

    /**
     * Terminates this pool for good: closes every socket directly (awaited, not fire-and-forget)
     * and cancels the pool's internal scope. Unlike [closePool] — which leaves the scope alive so
     * a long-lived pool can be repopulated later via [changeRelays], e.g. on re-login — a
     * destroyed pool can never be reused: its `scope.launch { }` calls silently become no-ops
     * forever after this. Only call this on a disposable, one-shot pool (e.g. the per-request
     * custom pools RelaysSocketManager builds for NIP-17 DM operations), never on a pool meant to
     * outlive a single operation.
     */
    suspend fun destroy() {
        socketClients.forEach { client -> runCatching { client.close() } }
        socketClients = emptyList()
        relays = emptyList()
        scope.cancel()
    }

    fun hasRelays() = relays.isNotEmpty()

    suspend fun tryConnectingToRelay(url: String) {
        runCatching {
            val normalizedUrl = url.normalizedRelayUrl()
            socketClients.find { it.socketUrl.normalizedRelayUrl() == normalizedUrl }
                ?.ensureSocketConnectionOrThrow()
        }
    }

    private fun List<Relay>.mapAsNostrSocketClient() =
        this.map { relay ->
            nostrSocketClientFactory.create(
                wssUrl = relay.url,
                onSocketConnectionOpened = onSocketConnectionOpenedCallback,
                onSocketConnectionClosed = onSocketConnectionClosedCallback,
            ).also { client -> observeAuthChallenges(client) }
        }

    /**
     * Answers this relay's AUTH challenges for as long as the client lives.
     *
     * A relay can send `["AUTH", challenge]` unprompted at any point, commonly the moment a query
     * for privacy-sensitive content arrives — a direct-message REQ, in the case this exists for.
     * Nothing ever answered it: the wire-level `sendAUTH` has existed since this client was
     * written, with nothing upstream ever calling it, so every relay that requires NIP-42 before
     * returning DMs returned nothing, silently, forever. That read from outside as "half my
     * conversations are missing" with no error anywhere to explain why.
     */
    private fun observeAuthChallenges(client: NostrSocketClient) {
        val sign = signAuthEvent ?: return
        scope.launch {
            client.incomingMessages
                .filterIsInstance<NostrIncomingMessage.AuthMessage>()
                .collect { challenge ->
                    // Bounded so a signer that never answers — an external signer app with no UI
                    // listening for this specific request — cannot hang this collector, or the
                    // shared signing lock other, unrelated signing requests also wait on.
                    val signed = withTimeoutOrNull(AUTH_SIGN_TIMEOUT_MS) {
                        runCatching { sign(challenge.challenge, client.socketUrl) }.getOrNull()
                    }
                    if (signed != null) {
                        runCatching { client.sendAUTH(signed.toNostrJsonObject()) }
                    }
                }
        }
    }

    @Throws(NostrPublishException::class)
    suspend fun publishEvent(nostrEvent: NostrEvent) {
        val clients = writeClients()
        if (clients.isEmpty()) {
            throw NostrPublishException(cause = IllegalStateException("no write relays"))
        }
        handlePublishEventToRelays(clients, nostrEvent)
    }

    @OptIn(FlowPreview::class)
    private suspend fun handlePublishEventToRelays(relayConnections: List<NostrSocketClient>, nostrEvent: NostrEvent) {
        val responseFlow = MutableSharedFlow<NostrPublishResult>()
        relayConnections.forEach { nostrSocketClient ->
            scope.launch {
                with(nostrSocketClient) {
                    val sendEventResult = runCatching {
                        publishEventAndAwaitResponse(eventId = nostrEvent.id, event = nostrEvent.toNostrJsonObject())
                            .timeout(PUBLISH_TIMEOUT.milliseconds)
                            .first()
                    }
                    sendEventResult.getOrNull()?.let {
                        responseFlow.emit(NostrPublishResult(result = it))
                    }
                    sendEventResult.exceptionOrNull()?.let {
                        Napier.w(throwable = it) { "sendEVENT failed to $socketUrl" }
                        responseFlow.emit(NostrPublishResult(error = it))
                    }
                }
            }
        }

        var responseCount = 0
        responseFlow.timeout(PUBLISH_TIMEOUT.milliseconds)
            .catch { throw NostrPublishException(cause = it) }
            .transform {
                emit(it)
                responseCount++
                if (relayConnections.size == responseCount && !it.isSuccessful()) {
                    throw NostrPublishException(cause = null)
                }
            }
            .first { it.isSuccessful() }
    }

    private fun NostrPublishResult.isSuccessful(): Boolean {
        return result is NostrIncomingMessage.OkMessage && result.success
    }

    suspend fun query(
        filter: JsonObject,
        timeoutMs: Long = SUBSCRIBE_TIMEOUT.toLong(),
    ): RelayPoolQueryResult {
        val safeFilter = filter.withSafeLimit()
        val requestedCount = safeFilter["limit"]?.jsonPrimitive?.intOrNull ?: MAX_EVENTS_PER_QUERY
        val safeTimeoutMs = timeoutMs.coerceIn(MIN_QUERY_TIMEOUT_MS, SUBSCRIBE_TIMEOUT.toLong())
        val clients = readClients()
        if (clients.isEmpty()) return RelayPoolQueryResult()

        // Bounded by queryGate rather than left to fire the moment a caller asks: this is the
        // one choke point every fetcher in the app shares (see MAX_CONCURRENT_QUERIES).
        return queryGate.withPermit {
            val subscriptionId = subscriptionIdFactory()
            if (!tryAcquireSubscription()) {
                RelayPoolQueryResult()
            } else {
                try {
                    val result = collectUntilEose(
                        clients = clients,
                        subscriptionId = subscriptionId,
                        filter = safeFilter,
                        timeoutMs = safeTimeoutMs,
                        requestedCount = requestedCount,
                    )
                    publishQueryStats(requested = clients.size, result = result)
                    result
                } finally {
                    activeSubscriptions.decrementAndGet()
                    closeSubscription(clients, subscriptionId)
                }
            }
        }
    }

    /** Prevent a malformed/custom filter from turning one UI action into an unbounded relay dump. */
    private fun JsonObject.withSafeLimit(): JsonObject = buildJsonObject {
        forEach { (key, value) -> if (key != "limit") put(key, value) }
        val requested = this@withSafeLimit["limit"]?.jsonPrimitive?.intOrNull
        put("limit", JsonPrimitive((requested ?: MAX_EVENTS_PER_QUERY).coerceIn(1, MAX_EVENTS_PER_QUERY)))
    }

    /**
     * Keeps a Nostr REQ open on every read relay and forwards events until the
     * collector cancels. The socket subscription helper re-sends REQ after a
     * reconnect, so live feeds survive transient relay disconnects.
     */
    fun subscribe(filter: JsonObject): kotlinx.coroutines.flow.Flow<NostrEvent> = channelFlow {
        val clients = readClients()
        if (clients.isEmpty()) return@channelFlow

        val rootSubscriptionId = subscriptionIdFactory()
        if (!tryAcquireSubscription()) return@channelFlow
        val safeFilter = filter.withSafeLimit()
        clients.forEachIndexed { index, client ->
            val subscriptionId = "$rootSubscriptionId-$index"
            launch {
                try {
                    client.subscription(subscriptionId = subscriptionId, data = safeFilter).collect { message ->
                        when (message) {
                            is NostrIncomingMessage.EventMessage -> message.nostrEvent?.let { send(it) }
                            is NostrIncomingMessage.EventsMessage -> message.nostrEvents.forEach { send(it) }
                            is NostrIncomingMessage.NoticeMessage ->
                                Napier.w { "NOTICE from ${client.socketUrl}: ${message.message}" }
                            else -> Unit
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Napier.w(throwable = error) { "SUBSCRIBE failed on ${client.socketUrl}" }
                }
            }
        }

        awaitClose {
            activeSubscriptions.decrementAndGet()
            clients.forEachIndexed { index, client ->
                scope.launch { runCatching { client.sendCLOSE("$rootSubscriptionId-$index") } }
            }
        }
    }

    private fun readClients(): List<NostrSocketClient> = clientsFor { it.read }

    private fun tryAcquireSubscription(): Boolean {
        val count = activeSubscriptions.incrementAndGet()
        if (count <= MAX_ACTIVE_SUBSCRIPTIONS) return true
        activeSubscriptions.decrementAndGet()
        Napier.w { "Relay subscription limit reached; request ignored." }
        return false
    }

    private fun writeClients(): List<NostrSocketClient> = clientsFor { it.write }

    private fun clientsFor(predicate: (Relay) -> Boolean): List<NostrSocketClient> {
        val urls = relays.filter(predicate).map { it.url.normalizedRelayUrl() }.toSet()
        return when {
            relays.isEmpty() -> socketClients
            urls.isEmpty() -> emptyList()
            else -> socketClients.filter { it.socketUrl.normalizedRelayUrl() in urls }
        }
    }

    /**
     * Relay identity must use the same canonical form as [NostrSocketClient.socketUrl]. The socket
     * client removes trailing slashes, so comparing its URL with an unnormalised NIP-65/NIP-10050
     * value made an otherwise valid write relay disappear from [writeClients].
     */
    private fun String.normalizedRelayUrl(): String = trim().cleanWebSocketUrl()

    @Suppress("LongParameterList")
    private suspend fun collectUntilEose(
        clients: List<NostrSocketClient>,
        subscriptionId: String,
        filter: JsonObject,
        timeoutMs: Long,
        requestedCount: Int,
    ): RelayPoolQueryResult {
        val eventsById = LinkedHashMap<String, NostrEvent>()
        val eoseRelays = mutableSetOf<String>()
        val failedRelays = mutableMapOf<String, String>()
        val completedRelays = mutableSetOf<String>()
        var duplicates = 0
        val mutex = Mutex()
        // Completing on the *first* EOSE discarded whatever the slower relays still had, and the
        // loss grew with the size of the pool: with four relays the spread between first and last
        // is small, with a dozen the first arrives long before the rest. Waiting for a quorum
        // keeps the result representative of the pool without waiting for its slowest member.
        val quorum = (clients.size + 1) / 2
        val quorumReached = CompletableDeferred<Boolean>()
        val firstEoseOrAllFailed = CompletableDeferred<Boolean>()
        val allRelaysCompleted = CompletableDeferred<Unit>()
        // A page that already holds everything the caller asked for is a complete answer. Without
        // this the query kept paying the EOSE grace and, on an empty first EOSE, waited for the
        // slowest relay — on every request, even when the first relay had already delivered.
        val pageFull = CompletableDeferred<Unit>()

        supervisorScope {
            clients.forEach { client ->
                launch {
                    queryOneRelayInto(
                        client = client,
                        subscriptionId = subscriptionId,
                        filter = filter,
                        timeoutMs = timeoutMs,
                        mutex = mutex,
                        eventsById = eventsById,
                        eoseRelays = eoseRelays,
                        failedRelays = failedRelays,
                        completedRelays = completedRelays,
                        clientCount = clients.size,
                        quorum = quorum,
                        requestedCount = requestedCount,
                        firstEoseOrAllFailed = firstEoseOrAllFailed,
                        quorumReached = quorumReached,
                        allRelaysCompleted = allRelaysCompleted,
                        pageFull = pageFull,
                        onDuplicate = { duplicates += 1 },
                    )
                }
            }
            val hadEose = withTimeoutOrNull(timeoutMs) { firstEoseOrAllFailed.await() } ?: false
            if (hadEose && !pageFull.isCompleted) {
                // Give the rest of the pool its turn before settling, unless the page is already
                // full. Bounded by the same timeout, so a dead relay cannot hold the screen.
                withTimeoutOrNull(timeoutMs) { quorumReached.await() }
            }
            if (hadEose && !pageFull.isCompleted) {
                // EOSE with zero events is valid. Keep slower relays alive in that case;
                // otherwise the first empty relay could hide events available elsewhere.
                delay(FIRST_EOSE_GRACE_MS)
                val hasEvents = mutex.withLock { eventsById.isNotEmpty() }
                if (!hasEvents) {
                    // The outer timeout still bounds this wait. Callers can apply a tighter
                    // timeout when a UI operation must return sooner.
                    withTimeoutOrNull(timeoutMs) { allRelaysCompleted.await() }
                }
            }
            coroutineContext.cancelChildren()
        }

        return RelayPoolQueryResult(
            events = eventsById.values.toList(),
            eoseRelays = eoseRelays.toSet(),
            failedRelays = failedRelays.toMap(),
            duplicateCount = duplicates,
        )
    }

    @Suppress("LongParameterList")
    private suspend fun queryOneRelayInto(
        client: NostrSocketClient,
        subscriptionId: String,
        filter: JsonObject,
        timeoutMs: Long,
        mutex: Mutex,
        eventsById: MutableMap<String, NostrEvent>,
        eoseRelays: MutableSet<String>,
        failedRelays: MutableMap<String, String>,
        completedRelays: MutableSet<String>,
        clientCount: Int,
        quorum: Int,
        requestedCount: Int,
        firstEoseOrAllFailed: CompletableDeferred<Boolean>,
        quorumReached: CompletableDeferred<Boolean>,
        allRelaysCompleted: CompletableDeferred<Unit>,
        pageFull: CompletableDeferred<Unit>,
        onDuplicate: () -> Unit,
    ) {
        queryOneRelay(
            client = client,
            subscriptionId = subscriptionId,
            filter = filter,
            timeoutMs = timeoutMs,
            onEvent = { event ->
                val reachedTarget = mutex.withLock {
                    when {
                        eventsById.containsKey(event.id) -> onDuplicate()
                        eventsById.size >= MAX_EVENTS_PER_QUERY -> Unit
                        else -> eventsById[event.id] = event
                    }
                    eventsById.size >= requestedCount
                }
                if (reachedTarget) {
                    pageFull.complete(Unit)
                    firstEoseOrAllFailed.complete(true)
                }
            },
            onEose = {
                val reachedQuorum = mutex.withLock {
                    eoseRelays += client.socketUrl
                    completedRelays += client.socketUrl
                    if (completedRelays.size >= clientCount) allRelaysCompleted.complete(Unit)
                    completedRelays.size >= quorum
                }
                if (reachedQuorum) quorumReached.complete(true)
                firstEoseOrAllFailed.complete(true)
            },
            onFailure = { reason ->
                // A relay that failed has had its turn: it counts towards the quorum, otherwise
                // one dead relay would make every query wait out the full timeout.
                val (allFailed, reachedQuorum) = mutex.withLock {
                    failedRelays[client.socketUrl] = reason
                    completedRelays += client.socketUrl
                    if (completedRelays.size >= clientCount) allRelaysCompleted.complete(Unit)
                    (eoseRelays.isEmpty() && failedRelays.size >= clientCount) to
                        (completedRelays.size >= quorum)
                }
                if (reachedQuorum) quorumReached.complete(true)
                if (allFailed) firstEoseOrAllFailed.complete(false)
            },
        )
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun queryOneRelay(
        client: NostrSocketClient,
        subscriptionId: String,
        filter: JsonObject,
        timeoutMs: Long,
        onEvent: suspend (NostrEvent) -> Unit,
        onEose: suspend () -> Unit,
        onFailure: suspend (String) -> Unit,
    ) {
        try {
            client.ensureSocketConnectionOrThrow()
            withTimeout(timeoutMs) {
                client.incomingMessages
                    .onSubscription { client.sendREQ(subscriptionId = subscriptionId, data = filter) }
                    .filterBySubscriptionId(subscriptionId)
                    .transformWhile { message ->
                        emit(message)
                        message !is NostrIncomingMessage.EoseMessage
                    }
                    .collect { message ->
                        if (consumeQueryMessage(message, client, onEvent)) onEose()
                    }
            }
            // Sent the moment THIS relay reaches its own EOSE, instead of only from the shared
            // closeSubscription() once every relay in the call has settled. A relay that answers
            // in well under a second (confirmed via an isolated probe: offchain.pub does) was
            // still sitting with its subscription formally open for up to [timeoutMs] whenever any
            // other relay in the same call was slower — and on a page needing dozens of overlapping
            // query() calls, that is enough open-but-already-answered subscriptions stacking up on
            // one connection to trip its concurrent-REQ limit. The same probe, same relay, same
            // request pattern: closing this early took a run from 20 "too many concurrent REQs"
            // rejections down to zero. closeSubscription() below still runs for every relay as a
            // safety net; sending CLOSE twice for one already-closed subscription is a harmless
            // no-op.
            runCatching { client.sendCLOSE(subscriptionId) }
        } catch (_: TimeoutCancellationException) {
            Napier.i { "REQ timeout on ${client.socketUrl}" }
            runCatching { client.sendCLOSE(subscriptionId) }
            onFailure("timeout")
        } catch (error: RelayRejectedRequestException) {
            // A rejected REQ never gets an EOSE — without this, the collector above just sat
            // waiting on a subscription that would never answer until the full [timeoutMs]
            // elapsed, indistinguishable from a genuinely slow relay. On a relay that rate-limits
            // aggressively (confirmed via captured device logs: nostr.mom and offchain.pub reject
            // outright once too many REQs land on the same connection close together) that turned
            // one rejected relay into an 8-second dead wait, repeated on every query stage of a
            // single pull-to-refresh. Failing the moment the relay says so — rather than waiting
            // it out — is what actually removes that stall; MAX_CONCURRENT_QUERIES above only
            // reduces how often the rejection happens in the first place.
            Napier.i { "REQ rejected on ${client.socketUrl}: ${error.notice}" }
            onFailure("rejected")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w(throwable = error) { "REQ failed on ${client.socketUrl}" }
            onFailure(error.message ?: "error")
        }
    }

    private suspend fun consumeQueryMessage(
        message: NostrIncomingMessage,
        client: NostrSocketClient,
        onEvent: suspend (NostrEvent) -> Unit,
    ): Boolean {
        when (message) {
            is NostrIncomingMessage.EventMessage -> message.nostrEvent?.let { onEvent(it) }
            is NostrIncomingMessage.EventsMessage -> message.nostrEvents.forEach { onEvent(it) }
            is NostrIncomingMessage.EoseMessage -> return true
            is NostrIncomingMessage.NoticeMessage -> {
                Napier.w { "NOTICE from ${client.socketUrl}: ${message.message}" }
                val notice = message.message
                if (notice != null && notice.isRelayRejectionNotice()) {
                    throw RelayRejectedRequestException(notice)
                }
            }
            else -> Unit
        }
        return false
    }

    /**
     * A relay can NOTICE a REQ it has no intention of ever answering — most commonly a
     * concurrency cap ("too many concurrent REQs", seen from nostr.mom and offchain.pub), or the
     * standardized NIP-01 `rate-limited:`/`blocked:` prefixes other relays use for the same thing.
     * Matched loosely on purpose: relay NOTICE wording is not standardized, and treating a false
     * positive as "this relay failed" costs nothing a real timeout would not have cost anyway,
     * while missing a real rejection costs the full [SUBSCRIBE_TIMEOUT].
     */
    private fun String.isRelayRejectionNotice(): Boolean {
        val normalized = lowercase()
        return normalized.contains("too many concurrent") ||
            normalized.startsWith("rate-limited:") ||
            normalized.startsWith("blocked:")
    }

    private class RelayRejectedRequestException(val notice: String) : Exception(notice)

    private suspend fun closeSubscription(clients: List<NostrSocketClient>, subscriptionId: String) {
        clients.forEach { client ->
            runCatching { client.sendCLOSE(subscriptionId) }
        }
    }

    private fun publishQueryStats(requested: Int, result: RelayPoolQueryResult) {
        val stats = RelayQueryStats(
            requestedRelays = requested,
            eoseRelays = result.eoseRelays.size,
            failedRelays = result.failedRelays.size,
            uniqueEvents = result.uniqueEventCount,
            duplicates = result.duplicateCount,
            timedOut = result.failedRelays.values.any { it == "timeout" },
        )
        _lastQueryStats.value = stats
        Napier.i {
            "RelayPool query relays=${stats.requestedRelays} eose=${stats.eoseRelays} " +
                "fail=${stats.failedRelays} events=${stats.uniqueEvents} dups=${stats.duplicates}"
        }
    }
}
