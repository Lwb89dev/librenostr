package net.primal.android.auth.repository

import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.primal.android.signer.bunker.BunkerConnection
import net.primal.android.signer.bunker.BunkerLoginClient
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.CredentialType
import net.primal.android.user.repository.RelayRepository
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.utils.assureValidNsec
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp

class LoginHandler @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val dispatchers: DispatcherProvider,
    private val credentialsStore: CredentialsStore,
    private val relayRepository: RelayRepository,
    private val bunkerLoginClient: BunkerLoginClient,
) {
    @Suppress("UNUSED_PARAMETER", "TooGenericExceptionCaught")
    suspend fun login(
        nostrKey: String,
        credentialType: CredentialType,
        authorizationEvent: NostrEvent?,
    ) = withContext(dispatchers.io()) {
        val userId = saveCredentials(credentialType = credentialType, nostrKey = nostrKey)
        try {
            completeLogin(
                userId = userId,
                nostrKey = nostrKey,
                credentialType = credentialType,
            )
        } catch (exception: Exception) {
            removeCredentials(credentialType = credentialType, nostrKey = nostrKey)
            throw exception
        }
    }

    private suspend fun completeLogin(
        userId: String,
        nostrKey: String,
        credentialType: CredentialType,
    ) {
        runCatching { relayRepository.ensureLocalBootstrapRelays(userId) }
        userRepository.ensureLocalUserAccount(userId)
        activateAccount(credentialType = credentialType, nostrKey = nostrKey)
    }

    private suspend fun activateAccount(credentialType: CredentialType, nostrKey: String) {
        when (credentialType) {
            CredentialType.ExternalSigner -> authRepository.loginWithExternalSignerNpub(npub = nostrKey)
            CredentialType.PublicKey -> authRepository.loginWithNpub(npub = nostrKey)
            CredentialType.PrivateKey -> authRepository.loginWithNsec(nostrKey = nostrKey)
            CredentialType.InternalSigner, CredentialType.RemoteSigner -> Unit
        }
    }

    private suspend fun saveCredentials(credentialType: CredentialType, nostrKey: String): String {
        return when (credentialType) {
            CredentialType.ExternalSigner -> credentialsStore.saveExternalSignerNpub(npub = nostrKey)

            CredentialType.PublicKey -> credentialsStore.saveNpub(npub = nostrKey)

            CredentialType.PrivateKey -> credentialsStore.saveNsec(nostrKey = nostrKey)

            CredentialType.InternalSigner -> error("Can't login with InternalSigner key.")

            CredentialType.RemoteSigner -> error("Bunker connections go through loginWithBunker().")
        }
    }

    private suspend fun removeCredentials(credentialType: CredentialType, nostrKey: String) {
        when (credentialType) {
            CredentialType.PublicKey, CredentialType.ExternalSigner ->
                credentialsStore.removeCredentialByNpub(npub = nostrKey)

            CredentialType.PrivateKey -> credentialsStore.removeCredentialByNsec(nsec = nostrKey.assureValidNsec())

            CredentialType.InternalSigner, CredentialType.RemoteSigner -> Unit
        }
    }

    /**
     * A bunker connection doesn't fit [login]'s single `nostrKey` shape: the handshake with the
     * bunker has to happen first, over a relay, before there's even a `userId` to save credentials
     * under — nsec/npub/Amber all know their identity from the input alone.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun loginWithBunker(bunkerUrl: String): String =
        withContext(dispatchers.io()) {
            val connection = bunkerLoginClient.connect(bunkerUrl)
            val userId = credentialsStore.saveRemoteSignerConnection(
                userPubkeyHex = connection.userPubkeyHex,
                remoteSignerPubkey = connection.bunkerPubkey,
                relays = connection.relays,
                secret = connection.secret,
                clientPrivateKeyHex = connection.clientKeyPair.privateKey,
            )
            try {
                completeBunkerLogin(connection = connection, userId = userId)
                userId
            } catch (exception: Exception) {
                credentialsStore.removeCredentialByNpub(npub = userId.hexToNpubHrp())
                throw exception
            }
        }

    private suspend fun completeBunkerLogin(connection: BunkerConnection, userId: String) {
        runCatching { relayRepository.ensureLocalBootstrapRelays(userId) }
        userRepository.ensureLocalUserAccount(userId)
        authRepository.loginWithRemoteSigner(
            userPubkeyHex = connection.userPubkeyHex,
            remoteSignerPubkey = connection.bunkerPubkey,
            relays = connection.relays,
            secret = connection.secret,
            clientPrivateKeyHex = connection.clientKeyPair.privateKey,
        )
    }
}
