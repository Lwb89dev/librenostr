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
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
    private val connectionBackoff: RelayConnectionBackoff = RelayConnectionBackoff.Shared,
) : NostrSocketClient {

    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io())

    private val wsMutex = Mutex()
    private var wsSession: WebSocketSession? = null
    private var wsReceiverJob: Job? = null

    /** The dial currently in flight, shared by every caller that needs a session meanwhile. */
    @Volatile
    private var pendingConnect: Deferred<Unit>? = null

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

    /**
     * Makes sure a live session exists, opening one if needed — but never more than one dial at a
     * time, and never a dial for a relay that is still serving out a [RelayConnectionBackoff].
     *
     * The handshake used to run *inside* [wsMutex], awaited by whichever caller happened to arrive
     * first, with every other caller queued on the lock behind it. Against a relay that silently
     * drops packets that is a wait of up to the OkHttp connect timeout (10 s, 30 s over Tor) per
     * caller, back to back, and the callers were queries holding one of the relay pool's few
     * query slots — so one unreachable relay could freeze every feed, notification and DM load in
     * the app. Two changes fix that:
     * - the dial now runs in this client's own scope as a single shared attempt ([pendingConnect]);
     *   every concurrent caller awaits that same attempt, and a caller giving up (its query hit
     *   its deadline) no longer cancels the dial for everyone else, so it can still finish and
     *   record whether the relay is reachable;
     * - after a failed dial the relay is not dialled again until its backoff window has passed;
     *   until then this throws [RelayBackingOffException] immediately, which the pool treats as
     *   that relay failing its share of the query — in microseconds, not seconds.
     */
    override suspend fun ensureSocketConnectionOrThrow() {
        if (hasUsableSession()) return

        val attempt = wsMutex.withLock {
            if (hasUsableSession()) return
            pendingConnect?.takeIf { it.isActive } ?: startConnectAttemptLocked()
        }
        try {
            attempt.await()
        } catch (error: CancellationException) {
            // Either this caller was cancelled (rethrown as is by ensureActive), or the shared dial
            // was — by close(), while this caller still wants an answer. The latter is a failed
            // connection from this caller's point of view, not a reason for it to stop.
            currentCoroutineContext().ensureActive()
            throw NetworkException("Connection to $socketUrl was abandoned.", error)
        }
    }

    private fun hasUsableSession(): Boolean = wsSession?.isActive == true && !isSocketStale()

    /** Call under [wsMutex]. Refuses a relay in backoff; otherwise starts the one shared dial. */
    private fun startConnectAttemptLocked(): Deferred<Unit> {
        connectionBackoff.remainingDelay(socketUrl)?.let { remaining ->
            throw RelayBackingOffException(url = socketUrl, remaining = remaining)
        }
        // The previous session is dead or wedged; drop it now rather than after the new
        // handshake, so nothing keeps sending into it while the dial is in flight.
        cancelSocketSession()
        return scope.async { connectOnce() }.also { pendingConnect = it }
    }

    /**
     * One dial, run in [scope] rather than in any caller's coroutine. Installs the session under
     * [wsMutex] on success; records the outcome in [connectionBackoff] either way.
     */
    private suspend fun connectOnce() {
        val session = dial()
        try {
            wsMutex.withLock {
                wsSession = session
                lastSentMark = null
                lastReceivedMark = null
                session.launchWebSocketReceiver()
            }
        } catch (error: CancellationException) {
            // close() landed between the handshake and the install: nobody will ever use or close
            // this session, so it is torn down here instead of leaking until the relay drops it.
            session.cancel()
            throw error
        }
        connectionBackoff.recordSuccess(socketUrl)
        onSocketConnectionOpened?.invoke(socketUrl)
        // Bump once the session is live so collectors re-subscribe on a ready socket.
        _connectionGeneration.value += 1
    }

    /** The handshake itself; any failure comes back as the [NetworkException] callers see. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun dial(): WebSocketSession {
        val failure: Throwable = try {
            // A backstop only: OkHttp's own connect/read timeouts normally end a dead dial first.
            // Without it a dial that never resolves would be awaited by every future caller.
            return withTimeout(CONNECT_ATTEMPT_TIMEOUT) { httpClient.webSocketSession(urlString = socketUrl) }
        } catch (error: TimeoutCancellationException) {
            error
        } catch (error: CancellationException) {
            // Cancellation is control flow (the client was closed), not a transport failure.
            throw error
        } catch (error: Exception) {
            error
        }
        throw connectionFailed(failure)
    }

    private fun connectionFailed(error: Throwable): NetworkException {
        Napier.w("NostrSocketClient::connect($socketUrl) failed.", error)
        connectionBackoff.recordFailure(url = socketUrl, refusedByServer = error.isUpgradeRefusal())
        onSocketConnectionClosed?.invoke(socketUrl, error)
        return NetworkException(cause = error)
    }

    /**
     * Whether the relay answered the WebSocket upgrade with a plain HTTP status instead of
     * switching protocols — reachable, but refusing (a 503 from an overloaded relay, a 403, …).
     * Matched on the message because the exception type differs per engine: OkHttp throws a
     * `ProtocolException("Expected HTTP 101 response but was '503 …'")`, CIO a Ktor
     * `WebSocketException("… expected status code 101 but was 503")`.
     */
    private fun Throwable.isUpgradeRefusal(): Boolean =
        generateSequence(this) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { error -> error.message?.let { UPGRADE_REFUSAL.containsMatchIn(it) } == true }

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
        // A dial still in flight would otherwise install a fresh session on a client that has
        // just been closed — a socket nobody uses, left open until the relay drops it.
        pendingConnect?.cancel()
        pendingConnect = null
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

    /**
     * @param expectsReply whether the relay answers this message. Only messages that do arm the
     *   silence watchdog ([isSocketStale]); see [sendCLOSE] for the one that does not.
     */
    private suspend fun sendMessage(text: String, expectsReply: Boolean = true) {
        require(text.length <= MAX_SOCKET_MESSAGE_CHARS) {
            "Outgoing WebSocket frame exceeds the 1 MiB safety limit."
        }
        if (expectsReply) {
            ensureSocketConnectionOrThrow()
        }
        wsSession?.let { session ->
            session.send(Frame.Text(text = text))
            if (expectsReply) lastSentMark = timeSource.markNow()
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

    /**
     * Sent only on the session that is already open, never by opening a new one.
     *
     * A subscription lives on one connection; if that connection is gone, so is the subscription,
     * and there is nothing left to close. Routing CLOSE through the normal connect-before-send path
     * made every finished query re-dial every dead relay just to tell it to stop — while still
     * holding its relay-pool query slot — which is how one unreachable relay ended up stalling every
     * query in the app.
     *
     * It also does not arm the silence watchdog: relays do not answer a CLOSE, so counting it as an
     * unanswered request made every socket look dead ten seconds after its last query finished, and
     * the next query tore down and re-handshook a perfectly healthy connection.
     */
    override suspend fun sendCLOSE(subscriptionId: String) =
        sendMessage(text = subscriptionId.buildNostrCLOSEMessage(), expectsReply = false)

    override suspend fun sendEVENT(signedEvent: JsonObject) = sendMessage(text = signedEvent.buildNostrEVENTMessage())

    override suspend fun sendAUTH(signedEvent: JsonObject) = sendMessage(text = signedEvent.buildNostrAUTHMessage())

    companion object {
        private const val MAX_SOCKET_MESSAGE_CHARS = 1024 * 1024

        /** Deep enough that a burst of events never blocks the socket's read loop. */
        private const val INCOMING_BUFFER_CAPACITY = 256

        /**
         * Longer than any legitimate handshake: OkHttp's connect + upgrade-read timeouts are 10 s
         * each on a direct connection and widened to 30 s each over Tor.
         */
        private val CONNECT_ATTEMPT_TIMEOUT = 75.seconds

        private const val MAX_CAUSE_DEPTH = 8
        private val UPGRADE_REFUSAL = Regex("""101.*but was""", RegexOption.IGNORE_CASE)
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
