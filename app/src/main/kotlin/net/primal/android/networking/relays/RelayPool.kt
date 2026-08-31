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
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.user.domain.Relay
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
) {

    companion object {
        const val PUBLISH_TIMEOUT = 10_000
        const val SUBSCRIBE_TIMEOUT = 8_000
        const val FIRST_EOSE_GRACE_MS = 400L
        const val MIN_QUERY_TIMEOUT_MS = 500L
        const val MAX_EVENTS_PER_QUERY = 500
        const val MAX_RELAYS = 30
        const val MAX_ACTIVE_SUBSCRIPTIONS = 64
    }

    private val scope = CoroutineScope(dispatchers.io())
    private val activeSubscriptions = AtomicInteger(0)

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
            .distinctBy { it.url }
            .filter { it.url.isValidRelayUrl() }
            .take(MAX_RELAYS)
        val existingRelayUrls = socketClients.map { it.socketUrl }
        val newRelayUrls = sanitized.map { it.url }

        val toAddRelayUrls = newRelayUrls.filter { it !in existingRelayUrls }
        val toAddSocketClients = sanitized.filter { it.url in toAddRelayUrls }.mapAsNostrSocketClient()
        val toRemoveSocketClients = socketClients.filter { it.socketUrl !in newRelayUrls }

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

    fun hasRelays() = relays.isNotEmpty()

    suspend fun tryConnectingToRelay(url: String) {
        runCatching {
            socketClients.find { it.socketUrl == url }?.ensureSocketConnectionOrThrow()
        }
    }

    private fun List<Relay>.mapAsNostrSocketClient() =
        this.map {
            nostrSocketClientFactory.create(
                wssUrl = it.url,
                onSocketConnectionOpened = onSocketConnectionOpenedCallback,
                onSocketConnectionClosed = onSocketConnectionClosedCallback,
            )
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
        val safeTimeoutMs = timeoutMs.coerceIn(MIN_QUERY_TIMEOUT_MS, SUBSCRIBE_TIMEOUT.toLong())
        val clients = readClients()
        if (clients.isEmpty()) return RelayPoolQueryResult()

        val subscriptionId = subscriptionIdFactory()
        if (!tryAcquireSubscription()) return RelayPoolQueryResult()
        try {
            val result = collectUntilEose(
                clients = clients,
                subscriptionId = subscriptionId,
                filter = safeFilter,
                timeoutMs = safeTimeoutMs,
            )
            publishQueryStats(requested = clients.size, result = result)
            return result
        } finally {
            activeSubscriptions.decrementAndGet()
            closeSubscription(clients, subscriptionId)
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
        val urls = relays.filter(predicate).map { it.url }.toSet()
        return when {
            relays.isEmpty() -> socketClients
            urls.isEmpty() -> emptyList()
            else -> socketClients.filter { it.socketUrl in urls }
        }
    }

    private suspend fun collectUntilEose(
        clients: List<NostrSocketClient>,
        subscriptionId: String,
        filter: JsonObject,
        timeoutMs: Long,
    ): RelayPoolQueryResult {
        val eventsById = LinkedHashMap<String, NostrEvent>()
        val eoseRelays = mutableSetOf<String>()
        val failedRelays = mutableMapOf<String, String>()
        val completedRelays = mutableSetOf<String>()
        var duplicates = 0
        val mutex = Mutex()
        val firstEoseOrAllFailed = CompletableDeferred<Boolean>()
        val allRelaysCompleted = CompletableDeferred<Unit>()

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
                        firstEoseOrAllFailed = firstEoseOrAllFailed,
                        allRelaysCompleted = allRelaysCompleted,
                        onDuplicate = { duplicates += 1 },
                    )
                }
            }
            val hadEose = withTimeoutOrNull(timeoutMs) { firstEoseOrAllFailed.await() } ?: false
            if (hadEose) {
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
        firstEoseOrAllFailed: CompletableDeferred<Boolean>,
        allRelaysCompleted: CompletableDeferred<Unit>,
        onDuplicate: () -> Unit,
    ) {
        queryOneRelay(
            client = client,
            subscriptionId = subscriptionId,
            filter = filter,
            timeoutMs = timeoutMs,
            onEvent = { event ->
                mutex.withLock {
                    when {
                        eventsById.containsKey(event.id) -> onDuplicate()
                        eventsById.size >= MAX_EVENTS_PER_QUERY -> Unit
                        else -> eventsById[event.id] = event
                    }
                }
            },
            onEose = {
                mutex.withLock {
                    eoseRelays += client.socketUrl
                    completedRelays += client.socketUrl
                    if (completedRelays.size >= clientCount) allRelaysCompleted.complete(Unit)
                }
                firstEoseOrAllFailed.complete(true)
            },
            onFailure = { reason ->
                val allFailed = mutex.withLock {
                    failedRelays[client.socketUrl] = reason
                    completedRelays += client.socketUrl
                    if (completedRelays.size >= clientCount) allRelaysCompleted.complete(Unit)
                    eoseRelays.isEmpty() && failedRelays.size >= clientCount
                }
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
        } catch (_: TimeoutCancellationException) {
            Napier.i { "REQ timeout on ${client.socketUrl}" }
            onFailure("timeout")
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
            is NostrIncomingMessage.NoticeMessage ->
                Napier.w { "NOTICE from ${client.socketUrl}: ${message.message}" }
            else -> Unit
        }
        return false
    }

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
