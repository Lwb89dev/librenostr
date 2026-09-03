package net.primal.android.signer.bunker

import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import net.primal.core.nips.encryption.service.NostrEncryptionService
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.account.signer.remote.BunkerChannel
import net.primal.data.account.signer.remote.connectToBunker
import net.primal.data.account.signer.remote.model.RemoteSignerMethodType
import net.primal.data.account.signer.remote.requestAndAwait
import net.primal.domain.nostr.cryptography.NostrKeyPair
import net.primal.domain.nostr.cryptography.utils.CryptoUtils

/** A working bunker connection: what a login flow needs to persist to sign as this account going forward. */
data class BunkerConnection(
    val userPubkeyHex: String,
    val bunkerPubkey: String,
    val relays: List<String>,
    val secret: String?,
    val clientKeyPair: NostrKeyPair,
)

/**
 * Performs the NIP-46 handshake a `bunker://` URI promises: connect, then ask who we're actually
 * signing as. A fresh throwaway keypair is generated for the channel every time — it identifies
 * this device to the bunker, never the account itself.
 */
class BunkerLoginClient @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val nostrEncryptionService: NostrEncryptionService,
) {
    suspend fun connect(bunkerUrl: String): BunkerConnection {
        val parsed = bunkerUrl.parseBunkerUrlOrThrow()
        val clientKeyPair = CryptoUtils.generateHexEncodedKeypair()

        val channel = BunkerChannel(relays = parsed.relays, clientKeyPair = clientKeyPair, bunkerPubkey = parsed.pubkey)
        return connectToBunker(
            channel = channel,
            dispatchers = dispatchers,
            nostrEncryptionService = nostrEncryptionService,
        ) { client ->
            client.requestAndAwait(
                method = RemoteSignerMethodType.Connect,
                params = listOfNotNull(parsed.pubkey, parsed.secret),
                timeout = HANDSHAKE_TIMEOUT,
            )

            val userPubkeyHex = client.requestAndAwait(
                method = RemoteSignerMethodType.GetPublicKey,
                params = emptyList(),
                timeout = HANDSHAKE_TIMEOUT,
            ) ?: error("The bunker did not return a public key.")

            BunkerConnection(
                userPubkeyHex = userPubkeyHex,
                bunkerPubkey = parsed.pubkey,
                relays = parsed.relays,
                secret = parsed.secret,
                clientKeyPair = clientKeyPair,
            )
        }
    }

    private companion object {
        val HANDSHAKE_TIMEOUT = 15.seconds
    }
}
