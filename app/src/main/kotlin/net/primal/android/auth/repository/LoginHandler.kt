package net.primal.android.auth.repository

import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.CredentialType
import net.primal.android.user.repository.RelayRepository
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.utils.assureValidNsec

class LoginHandler @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val dispatchers: DispatcherProvider,
    private val credentialsStore: CredentialsStore,
    private val relayRepository: RelayRepository,
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
            CredentialType.InternalSigner -> Unit
        }
    }

    private suspend fun saveCredentials(credentialType: CredentialType, nostrKey: String): String {
        return when (credentialType) {
            CredentialType.ExternalSigner -> credentialsStore.saveExternalSignerNpub(npub = nostrKey)

            CredentialType.PublicKey -> credentialsStore.saveNpub(npub = nostrKey)

            CredentialType.PrivateKey -> credentialsStore.saveNsec(nostrKey = nostrKey)

            CredentialType.InternalSigner -> error("Can't login with InternalSigner key.")
        }
    }

    private suspend fun removeCredentials(credentialType: CredentialType, nostrKey: String) {
        when (credentialType) {
            CredentialType.PublicKey, CredentialType.ExternalSigner ->
                credentialsStore.removeCredentialByNpub(npub = nostrKey)

            CredentialType.PrivateKey -> credentialsStore.removeCredentialByNsec(nsec = nostrKey.assureValidNsec())

            CredentialType.InternalSigner -> Unit
        }
    }
}
