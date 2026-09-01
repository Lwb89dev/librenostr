package net.primal.core.networking.sockets

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import okio.Buffer
import okio.GzipSink
import okio.Inflater
import okio.InflaterSource
import okio.buffer
import okio.use

internal val SILENCE_TIMEOUT = 10.seconds

internal class NostrSocketClientImpl(
    dispatcherProvider: DispatcherProvider,
    wssUrl: String,
    private val httpClient: HttpClient,
    private val incomingCompressionEnabled: Boolean = false,
    private val onSocketConnectionOpened: SocketConnectionOpenedCallback? = null,
    private val onSocketConnectionClosed: SocketConnectionClosedCallback? = null,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : NostrSocketClient {

    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io())

    private val wsMutex = Mutex()
    private var wsSession: WebSocketSession? = null
    private var wsReceiverJob: Job? = null

    @Volatile
    private var lastSentMark: ComparableTimeMark? = null

    @Volatile
    private var lastReceivedMark: ComparableTimeMark? = null

    // Buffered on purpose. With no buffer, emit suspends until every collector has processed the
    // value, and every concurrent query subscribes to every relay — so one socket's read loop
    // stalled behind N filters per frame, and the stall grew with both relay count and query
    // concurrency. A buffer keeps reading decoupled from consumption; SharedFlow still delivers
    // in emission order, so nothing is reordered and nothing is dropped.
    private val _incomingMessages = MutableSharedFlow<NostrIncomingMessage>(
        extraBufferCapacity = INCOMING_BUFFER_CAPACITY,
    )
    override val incomingMessages = _incomingMessages.asSharedFlow()

    private val _connectionGeneration = MutableStateFlow(0L)
    override val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()

    override val socketUrl = wssUrl.cleanWebSocketUrl()

    override suspend fun ensureSocketConnectionOrThrow() {
        if (wsSession?.isActive == true && !isSocketStale()) return

        wsMutex.withLock {
            if (wsSession == null || wsSession?.isActive == false || isSocketStale()) {
                cancelSocketSession()
                wsSession = acquireWebSocketSession(url = socketUrl)
                // Bump once the session is live so collectors re-subscribe on a ready socket.
                _connectionGeneration.value += 1
            }
        }
    }

    /**
     * Whether the connection has been silent long enough to be treated as dead.
     *
     * Stale when a send is still outstanding — nothing received since the last send — and that
     * send has gone unanswered for at least [SILENCE_TIMEOUT]. The window is measured from the
     * send, not from the last received frame: a reply can only arrive after the request, so
     * measuring from the older receive mark would charge preceding idle time against the request
     * and cut its response window short. Only the most recent outstanding send is tracked.
     *
     * Transport-level death that outlives this window is caught separately by the keepalive ping.
     */
    private fun isSocketStale(): Boolean {
        val sent = lastSentMark ?: return false
        val received = lastReceivedMark
        return (received == null || sent > received) &&
            sent.elapsedNow() >= SILENCE_TIMEOUT
    }

    private suspend fun acquireWebSocketSession(url: String): WebSocketSession {
        return try {
            httpClient.webSocketSession(urlString = url).apply {
                lastSentMark = null
                lastReceivedMark = null
                launchWebSocketReceiver()
                onSocketConnectionOpened?.invoke(url)
            }
        } catch (error: CancellationException) {
            // Cancellation is control flow, not a transport failure. Preserve it so callers
            // can stop relay work promptly instead of receiving a misleading NetworkException.
            throw error
        } catch (error: Exception) {
            Napier.w("NostrSocketClient::acquireWebSocketSession($socketUrl) failed.", error)
            close()
            onSocketConnectionClosed?.invoke(socketUrl, error)
            throw NetworkException(cause = error)
        }
    }

    private fun WebSocketSession.launchWebSocketReceiver() {
        wsReceiverJob?.cancel()
        wsReceiverJob = scope.launch {
            receiveSocketMessages()
        }
    }

    private suspend fun WebSocketSession.receiveSocketMessages() {
        try {
            for (frame in incoming) {
                lastReceivedMark = timeSource.markNow()
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        if (text.length > MAX_SOCKET_MESSAGE_CHARS) {
                            Napier.w { "Dropping oversized WS text frame from $socketUrl (${text.length} chars)." }
                        } else {
                            processIncomingMessage(text = text)
                        }
                    }

                    is Frame.Binary -> {
                        if (!incomingCompressionEnabled) {
                            Napier.w { "Ignoring unsolicited binary WS frame from $socketUrl." }
                        } else if (frame.data.size > MAX_SOCKET_MESSAGE_CHARS) {
                            Napier.w {
                                "Dropping oversized WS binary frame from $socketUrl (${frame.data.size} bytes)."
                            }
                        } else {
                            val decompressedMessage = decompressMessage(frame.data)
                            processIncomingMessage(text = decompressedMessage)
                        }
                    }

                    is Frame.Close -> {
                        val closeReason = frame.readReason()
                        Napier.w { "WS $socketUrl closed. [${closeReason?.code}, ${closeReason?.message}]" }
                        handleSocketTornDown(error = null)
                    }

                    else -> Unit
                }
            }
        } catch (error: CancellationException) {
            Napier.w("NostrSocketClient::receiveSocketMessages() on $socketUrl cancelled.")
            throw error
        } catch (error: Exception) {
            Napier.w("NostrSocketClient::receiveSocketMessages() on $socketUrl failed.", error)
            handleSocketTornDown(error = error)
        }
    }

    /**
     * Tears the session down from inside the receiver coroutine.
     *
     * [close] must not be used here: it cancels [wsReceiverJob], which is the coroutine running
     * this very function, and its suspending session close then throws CancellationException.
     * The project's `runCatching` rethrows cancellation, so both the session reset and
     * [onSocketConnectionClosed] were skipped and the pool kept reporting a dead relay as
     * connected. Cancelling the session is non-suspending, so nothing here can be interrupted.
     */
    private fun WebSocketSession.handleSocketTornDown(error: Throwable?) {
        // Only drop the reference when it still points at this session: a concurrent reconnect
        // may already have installed a fresh one that must not be nulled out here.
        if (wsSession === this) {
            wsSession = null
        }
        cancel()
        onSocketConnectionClosed?.invoke(socketUrl, error)
    }

    /**
     * Abruptly tears down the current session without the graceful close handshake.
     * Used on the reconnect path: the existing socket is already dead or wedged, so a
     * suspending [close] (which can block on the closing handshake of a half-open peer)
     * would only serialize recovery while holding [wsMutex]. Cancelling is non-suspending.
     */
    private fun cancelSocketSession() {
        wsReceiverJob?.cancel()
        wsReceiverJob = null
        wsSession?.cancel()
        wsSession = null
    }

    override suspend fun close() {
        wsReceiverJob?.cancel()
        wsReceiverJob = null
        runCatching {
            wsSession?.close(
                reason = CloseReason(
                    code = CloseReason.Codes.NORMAL,
                    message = "Closed by client.",
                ),
            )
        }
        wsSession = null
    }

    private suspend fun processIncomingMessage(text: String) {
        val parsed = text.parseIncomingMessage() ?: return
        // No pause before EOSE any more. It existed to give preceding EVENTs a chance to land
        // first, which was only necessary because the unbuffered flow dropped values when a
        // collector was not ready; it cost every socket 75 ms of blocked reading per EOSE.
        // Ordering is now guaranteed by the buffer.
        _incomingMessages.emit(value = parsed)
    }

    private suspend fun sendMessage(text: String, ensureSessionBeforeSend: Boolean = true) {
        require(text.length <= MAX_SOCKET_MESSAGE_CHARS) {
            "Outgoing WebSocket frame exceeds the 1 MiB safety limit."
        }
        if (ensureSessionBeforeSend) {
            ensureSocketConnectionOrThrow()
        }
        wsSession?.let { session ->
            session.send(Frame.Text(text = text))
            lastSentMark = timeSource.markNow()
        }
    }

    override suspend fun sendREQ(subscriptionId: String, data: JsonObject) {
        val reqMessage = data.buildNostrREQMessage(subscriptionId)
        return sendMessage(text = reqMessage)
    }

    override suspend fun sendCOUNT(data: JsonObject): String {
        val subscriptionId: String = Uuid.random().toPrimalSubscriptionId()
        val reqMessage = data.buildNostrCOUNTMessage(subscriptionId)
        sendMessage(text = reqMessage)
        return subscriptionId
    }

    override suspend fun sendCLOSE(subscriptionId: String) = sendMessage(text = subscriptionId.buildNostrCLOSEMessage())

    override suspend fun sendEVENT(signedEvent: JsonObject) = sendMessage(text = signedEvent.buildNostrEVENTMessage())

    override suspend fun sendAUTH(signedEvent: JsonObject) = sendMessage(text = signedEvent.buildNostrAUTHMessage())

    companion object {
        private const val MAX_SOCKET_MESSAGE_CHARS = 1024 * 1024

        /** Deep enough that a burst of events never blocks the socket's read loop. */
        private const val INCOMING_BUFFER_CAPACITY = 256
    }

    @Suppress("unused")
    private fun compressMessage(message: String): ByteArray {
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { sink ->
            sink.writeUtf8(message)
            sink.flush() // Ensure all data is written
        }
        return buffer.readByteArray()
    }

    private fun decompressMessage(compressedMessage: ByteArray): String {
        val buffer = Buffer().write(compressedMessage)
        InflaterSource(buffer, Inflater(false)).buffer().use { source ->
            val bytes = source.readByteArray((MAX_SOCKET_MESSAGE_CHARS + 1).toLong())
            if (bytes.size > MAX_SOCKET_MESSAGE_CHARS) {
                Napier.w { "Dropping decompressed WS payload exceeding cap from $socketUrl." }
                return ""
            }
            return bytes.decodeToString()
        }
    }

    private fun String.cleanWebSocketUrl(): String {
        return replace("https://", "wss://", ignoreCase = true)
            .replace("http://", "ws://", ignoreCase = true)
            .let { if (it.endsWith("/")) it.dropLast(1) else it }
    }
}
