package net.primal.data.account.signer.remote

import kotlin.time.Duration
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.networking.sockets.subscription
import net.primal.core.networking.sockets.toPrimalSubscriptionId
import net.primal.core.nips.encryption.service.NostrEncryptionService
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.account.signer.remote.model.RemoteSignerMethodRequest
import net.primal.data.account.signer.remote.model.RemoteSignerMethodResponse
import net.primal.data.account.signer.remote.model.RemoteSignerMethodType
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.cryptography.NostrKeyPair
import net.primal.domain.nostr.cryptography.signOrThrow
import net.primal.domain.nostr.cryptography.utils.assureValidNsec
import net.primal.domain.nostr.cryptography.utils.assureValidPubKeyHex
import net.primal.domain.nostr.serialization.toNostrJsonObject

/**
 * The client half of a NIP-46 channel: this app initiating requests to a remote signer (a
 * "bunker"), rather than [RemoteSignerClient]'s host role of answering requests from someone else.
 *
 * The wire format and crypto are identical in both directions, so this reuses the same request
 * and response models and the same NIP-44/kind-24133 envelope shape — only the roles are
 * reversed: [clientKeyPair] is this app's throwaway identity for the channel (never the account's
 * real key), and every request is addressed to [bunkerPubkey].
 */
class BunkerSignerClient(
    relayUrl: String,
    dispatchers: DispatcherProvider,
    private val clientKeyPair: NostrKeyPair,
    private val bunkerPubkey: String,
    private val nostrEncryptionService: NostrEncryptionService,
) {
    private val scope = CoroutineScope(dispatchers.io() + SupervisorJob())
    private val nostrSocketClient = NostrSocketClientFactory.create(
        wssUrl = relayUrl,
        keepAliveEnabled = true,
    )

    private val _responses = MutableSharedFlow<RemoteSignerMethodResponse>(extraBufferCapacity = RESPONSE_BUFFER)
    val responses = _responses.asSharedFlow()

    private var listenerJob: Job? = null

    suspend fun connect(): Result<Unit> =
        runCatching {
            nostrSocketClient.ensureSocketConnectionOrThrow()
            startSubscription()
        }

    private fun startSubscription() {
        listenerJob?.cancel()
        listenerJob = scope.launch {
            val listenerId = Uuid.random().toPrimalSubscriptionId()
            runCatching {
                nostrSocketClient.subscription(
                    subscriptionId = listenerId,
                    data = buildJsonObject {
                        put("kinds", buildJsonArray { add(NostrEventKind.NostrConnect.value) })
                        put("authors", buildJsonArray { add(bunkerPubkey) })
                        put("#p", buildJsonArray { add(clientKeyPair.pubKey.assureValidPubKeyHex()) })
                    },
                ).collect { message ->
                    if (message is NostrIncomingMessage.EventMessage) {
                        message.nostrEvent?.let { event -> handleIncomingEvent(event) }
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingEvent(event: NostrEvent) {
        val decrypted = nostrEncryptionService.nip44Decrypt(
            privateKey = clientKeyPair.privateKey,
            pubKey = event.pubKey,
            ciphertext = event.content,
        ).getOrNull() ?: return

        val response = decrypted.decodeFromJsonStringOrNull<RemoteSignerMethodResponse>() ?: return
        _responses.emit(response.assignClientPubKey(clientPubKey = event.pubKey))
    }

    /** Publishes [method]/[params] to the bunker, encrypted for [bunkerPubkey]. Returns the request id to await. */
    suspend fun sendRequest(method: RemoteSignerMethodType, params: List<String>): Result<String> =
        runCatching {
            val requestId = Uuid.random().toString()
            val request = RemoteSignerMethodRequest(id = requestId, method = method, params = params)
            val content = nostrEncryptionService.nip44Encrypt(
                privateKey = clientKeyPair.privateKey,
                pubKey = bunkerPubkey,
                plaintext = CommonJson.encodeToString(request),
            ).getOrThrow()

            val event = NostrUnsignedEvent(
                pubKey = clientKeyPair.pubKey,
                kind = NostrEventKind.NostrConnect.value,
                tags = listOf(bunkerPubkey.asPubkeyTag()),
                content = content,
            ).signOrThrow(nsec = clientKeyPair.privateKey.assureValidNsec())

            nostrSocketClient.ensureSocketConnectionOrThrow()
            nostrSocketClient.sendEVENT(signedEvent = event.toNostrJsonObject())
            requestId
        }

    /** Suspends for the bunker's answer to [requestId], or fails once [timeout] passes with nothing. */
    suspend fun awaitResponse(requestId: String, timeout: Duration): Result<RemoteSignerMethodResponse> =
        runCatching {
            withTimeoutOrNull(timeout) {
                responses.first { it.id == requestId }
            } ?: throw BunkerResponseTimeoutException(requestId = requestId)
        }

    suspend fun destroy() {
        nostrSocketClient.close()
        scope.cancel()
    }

    private companion object {
        const val RESPONSE_BUFFER = 16
    }
}

class BunkerResponseTimeoutException(requestId: String) : Exception("Timed out waiting for response to $requestId.")
