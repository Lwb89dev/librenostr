package net.primal.data.account.signer.remote

import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.primal.core.nips.encryption.service.NostrEncryptionService
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.data.account.signer.remote.model.RemoteSignerMethodResponse
import net.primal.data.account.signer.remote.model.RemoteSignerMethodType
import net.primal.domain.nostr.cryptography.NostrKeyPair

/** Who a bunker channel talks to and on which relays — everything [connectToBunker] needs but the transport. */
data class BunkerChannel(
    val relays: List<String>,
    val clientKeyPair: NostrKeyPair,
    val bunkerPubkey: String,
)

/**
 * Runs [block] against a bunker channel, trying [BunkerChannel.relays] in order until one
 * connects — a bunker URI can list more than one relay hint, and only some of them may be
 * reachable. Whichever relay the channel connects on, the client is torn down before this
 * returns, so [block] must finish everything it needs the channel for before returning.
 */
suspend fun <T> connectToBunker(
    channel: BunkerChannel,
    dispatchers: DispatcherProvider,
    nostrEncryptionService: NostrEncryptionService,
    block: suspend (BunkerSignerClient) -> T,
): T {
    require(channel.relays.isNotEmpty()) { "A bunker connection needs at least one relay." }

    var lastError: Throwable = IllegalStateException("No relay reachable for this bunker connection.")
    for (relay in channel.relays) {
        val client = BunkerSignerClient(
            relayUrl = relay,
            dispatchers = dispatchers,
            clientKeyPair = channel.clientKeyPair,
            bunkerPubkey = channel.bunkerPubkey,
            nostrEncryptionService = nostrEncryptionService,
        )
        val result = try {
            runCatching {
                client.connect().getOrThrow()
                block(client)
            }
        } finally {
            // Must run even if block() above was cancelled (e.g. the screen that started this
            // login was left) — otherwise the client's own socket and coroutine scope leak.
            withContext(NonCancellable) { client.destroy() }
        }

        if (result.isSuccess) return result.getOrThrow()
        lastError = result.exceptionOrNull() ?: lastError
    }
    throw lastError
}

/** Sends [method]/[params] and unwraps the bunker's answer, or throws if it declined the request. */
suspend fun BunkerSignerClient.requestAndAwait(
    method: RemoteSignerMethodType,
    params: List<String>,
    timeout: Duration,
): String? {
    val requestId = sendRequest(method = method, params = params).getOrThrow()
    return when (val response = awaitResponse(requestId = requestId, timeout = timeout).getOrThrow()) {
        is RemoteSignerMethodResponse.Success -> response.result
        is RemoteSignerMethodResponse.Error -> throw BunkerRequestRejectedException(response.error)
    }
}

class BunkerRequestRejectedException(message: String) : Exception(message)
