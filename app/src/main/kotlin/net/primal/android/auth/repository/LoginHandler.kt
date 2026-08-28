package net.primal.android.auth.repository

import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.settings.repository.SettingsRepository
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.CredentialType
import net.primal.android.user.repository.RelayRepository
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.bookmarks.PublicBookmarksRepository
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.mutes.MutedItemRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.utils.assureValidNsec
import net.primal.domain.nostr.cryptography.utils.getOrNull

@Suppress("LongParameterList")
class LoginHandler @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val mutedItemRepository: MutedItemRepository,
    private val bookmarksRepository: PublicBookmarksRepository,
    private val feedsRepository: FeedsRepository,
    private val dispatchers: DispatcherProvider,
    private val credentialsStore: CredentialsStore,
    private val nostrNotary: NostrNotary,
    private val relayRepository: RelayRepository,
) {
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
                authorizationEvent = authorizationEvent,
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
        authorizationEvent: NostrEvent?,
    ) {
        runCatching { relayRepository.syncUserRelaysOrBootstrap(userId) }

        if (credentialType == CredentialType.ExternalSigner) {
            activateAccount(credentialType = credentialType, nostrKey = nostrKey)
            return
        }

        hydrateUserSession(
            userId = userId,
            credentialType = credentialType,
            authorizationEvent = authorizationEvent,
        )
        activateAccount(credentialType = credentialType, nostrKey = nostrKey)
    }

    private suspend fun hydrateUserSession(
        userId: String,
        credentialType: CredentialType,
        authorizationEvent: NostrEvent?,
    ) {
        val authorizationEvent = authorizationEvent ?: nostrNotary.signAuthorizationNostrEvent(
            userId = userId,
            description = "Sync app settings",
        ).getOrNull()

        userRepository.fetchAndUpdateUserAccount(userId = userId)
        bookmarksRepository.fetchAndPersistBookmarks(userId = userId)
        authorizationEvent?.let { settingsRepository.fetchAndPersistAppSettings(it) }
        mutedItemRepository.fetchAndPersistMuteList(userId = userId)
        prefetchNoteFeeds(userId = userId, credentialType = credentialType)
    }

    private suspend fun activateAccount(credentialType: CredentialType, nostrKey: String) {
        when (credentialType) {
            CredentialType.ExternalSigner -> authRepository.loginWithExternalSignerNpub(npub = nostrKey)
            CredentialType.PublicKey -> authRepository.loginWithNpub(npub = nostrKey)
            CredentialType.PrivateKey -> authRepository.loginWithNsec(nostrKey = nostrKey)
            CredentialType.InternalSigner -> Unit
        }
    }

    /**
     * Fetches the user feeds before the account becomes active, so the home top app bar is populated
     * the moment we land on the main screen instead of filling in a beat later.
     *
     * Always resolves the actual user settings, never the defaults. [CredentialType.PublicKey] and
     * [CredentialType.InternalSigner] are skipped because they cannot produce the required signature.
     *
     * Failures are swallowed on purpose - unlike the fetches above, feeds must never fail a login.
     */
    private suspend fun prefetchNoteFeeds(userId: String, credentialType: CredentialType) {
        val timeout = credentialType.resolveFeedsPrefetchTimeout() ?: return

        runCatching {
            withTimeoutOrNull(timeout) {
                feedsRepository.fetchAndPersistNoteFeeds(userId = userId)
            } ?: Napier.w { "Note feeds prefetch timed out during login." }
        }.onFailure { error ->
            Napier.w(throwable = error) { "Failed to prefetch note feeds during login." }
        }
    }

    private fun CredentialType.resolveFeedsPrefetchTimeout(): Duration? =
        when (this) {
            CredentialType.PrivateKey -> NSEC_FEEDS_PREFETCH_TIMEOUT

            // Amber signs in the background when permitted, but may fall back to a foreground prompt
            // the user has to approve, hence the far more generous timeout.
            CredentialType.ExternalSigner -> EXTERNAL_SIGNER_FEEDS_PREFETCH_TIMEOUT

            CredentialType.PublicKey, CredentialType.InternalSigner -> null
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

    private companion object {
        private val NSEC_FEEDS_PREFETCH_TIMEOUT = 2.seconds
        private val EXTERNAL_SIGNER_FEEDS_PREFETCH_TIMEOUT = 30.seconds
    }
}
