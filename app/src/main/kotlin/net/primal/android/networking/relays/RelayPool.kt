package net.primal.android.networking.relays

import androidx.annotation.VisibleForTesting
import io.github.aakira.napier.Napier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.user.domain.Relay
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.NostrSocketClient
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.networking.sockets.SocketConnectionClosedCallback
import net.primal.core.networking.sockets.SocketConnectionOpenedCallback
import net.primal.core.networking.sockets.filterBySubscriptionId
import net.primal.core.networking.sockets.parseIncomingMessage
import net.primal.core.networking.sockets.publishEventAndAwaitResponse
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.serialization.toNostrJsonObject

class RelayPool(
    dispatchers: DispatcherProvider,
    private val nostrSocketClientFactory: NostrSocketClientFactory,
    private val cachingImportRepository: CachingImportRepository,
) {

    companion object {
        const val PUBLISH_TIMEOUT = 10_000
        const val SUBSCRIBE_TIMEOUT = 8_000
    }

    private val scope = CoroutineScope(dispatchers.io())
    private val activeSubscriptions = AtomicInteger(0)

    @VisibleForTesting
    var subscriptionIdFactory: () -> String = { Uuid.random().toString() }

    private val _lastQueryStats = MutableStateFlow<RelayQueryStats?>(null)
    val lastQueryStats = _lastQueryStats.asStateFlow()

    fun activeSubscriptionCount(): Int = activeSubscriptions.get()

    var relays: List<Relay> = emptyList()
        private set

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
        val existingRelayUrls = socketClients.map { it.socketUrl }
        val newRelayUrls = relays.map { it.url }

        val toAddRelayUrls = newRelayUrls.filter { it !in existingRelayUrls }
        val toAddSocketClients = relays.filter { it.url in toAddRelayUrls }.mapAsNostrSocketClient()
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
        this.relays = relays
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
    suspend fun publishEvent(nostrEvent: NostrEvent, cachingProxyEnabled: Boolean = false) {
        if (cachingProxyEnabled) {
            handleBroadcastEventThroughCachingProxy(relays.map { it.url }, nostrEvent)
        } else {
            handlePublishEventToRelays(socketClients, nostrEvent)
        }
    }

    private suspend fun handleBroadcastEventThroughCachingProxy(relayUrls: List<String>, nostrEvent: NostrEvent) {
        val result =
            cachingImportRepository.broadcastEvents(
                events = listOf(nostrEvent),
                relays = relayUrls,
            ).getOrNull()
                ?: throw NostrPublishException(
                    cause = NetworkException(message = "Primal NostrEvent 10_000_149 not found or invalid."),
                )

        result.find { response -> response.eventId == nostrEvent.id }?.responses
            ?.mapNotNull { relayResponse ->
                val relay = relayResponse.firstOrNull()
                val responseMessage = relayResponse.getOrNull(index = 1)?.parseIncomingMessage()
                if (relay != null && responseMessage != null) {
                    relay to responseMessage
                } else {
                    null
                }
            }
            ?.find { (_, relayMessage) -> relayMessage is NostrIncomingMessage.OkMessage && relayMessage.success }
            ?: throw NostrPublishException(
                cause = NetworkException("Event broadcast failed. Could not find success response from relays."),
            )
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
        val clients = readClients()
        if (clients.isEmpty()) return RelayPoolQueryResult()

        val subscriptionId = subscriptionIdFactory()
        activeSubscriptions.incrementAndGet()
        try {
            val result = collectUntilEose(
                clients = clients,
                subscriptionId = subscriptionId,
                filter = filter,
                timeoutMs = timeoutMs,
            )
            publishQueryStats(requested = clients.size, result = result)
            return result
        } finally {
            activeSubscriptions.decrementAndGet()
            closeSubscription(clients, subscriptionId)
        }
    }

    private fun readClients(): List<NostrSocketClient> {
        if (relays.isEmpty()) return socketClients
        val readUrls = relays.filter { it.read }.map { it.url }.toSet()
        if (readUrls.isEmpty()) return socketClients
        return socketClients.filter { it.socketUrl in readUrls }
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
        var duplicates = 0
        val mutex = Mutex()

        coroutineScope {
            clients.forEach { client ->
                launch {
                    queryOneRelay(
                        client = client,
                        subscriptionId = subscriptionId,
                        filter = filter,
                        timeoutMs = timeoutMs,
                        onEvent = { event ->
                            mutex.withLock {
                                if (eventsById.containsKey(event.id)) {
                                    duplicates += 1
                                } else {
                                    eventsById[event.id] = event
                                }
                            }
                        },
                        onEose = { mutex.withLock { eoseRelays += client.socketUrl } },
                        onFailure = { reason ->
                            mutex.withLock { failedRelays[client.socketUrl] = reason }
                        },
                    )
                }
            }
        }

        return RelayPoolQueryResult(
            events = eventsById.values.toList(),
            eoseRelays = eoseRelays.toSet(),
            failedRelays = failedRelays.toMap(),
            duplicateCount = duplicates,
        )
    }

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
                    .filterBySubscriptionId(subscriptionId)
                    .onStart { client.sendREQ(subscriptionId = subscriptionId, data = filter) }
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
