package net.primal.android.auth.repository

import io.github.aakira.napier.Napier
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.primal.android.networking.relays.FALLBACK_RELAY_URLS
import net.primal.android.profile.domain.ProfileMetadata
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.repository.BlossomRepository
import net.primal.android.user.repository.RelayRepository
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.onSuccess
import net.primal.core.utils.runCatching
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.nostr.cryptography.utils.assureValidNsec

class CreateAccountHandler @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val credentialsStore: CredentialsStore,
    private val authRepository: AuthRepository,
    private val relayRepository: RelayRepository,
    private val blossomRepository: BlossomRepository,
    private val userRepository: UserRepository,
    private val feedsRepository: FeedsRepository,
) {

    suspend fun createNostrAccount(
        privateKey: String,
        profileMetadata: ProfileMetadata,
        followedUserIds: Set<String>,
        preFetchedRelays: List<String>? = null,
        preFetchedNoteFeeds: List<PrimalFeed> = emptyList(),
    ) = withContext(dispatchers.io()) {
        runCatching {
            val userId = credentialsStore.saveNsec(nostrKey = privateKey)

            relayRepository.bootstrapUserRelays(userId, preFetchedRelays ?: FALLBACK_RELAY_URLS)

            coroutineScope {
                awaitAll(
                    async { blossomRepository.ensureBlossomServerList(userId) },
                    async {
                        userRepository.setProfileMetadata(
                            userId = userId,
                            profileMetadata = profileMetadata,
                        )
                    },
                    async { userRepository.setFollowList(userId = userId, contacts = setOf(userId) + followedUserIds) },
                    async {
                        persistDefaultNoteFeeds(userId = userId, preFetchedNoteFeeds = preFetchedNoteFeeds)
                    },
                )
            }
        }.onFailure { exception ->
            Napier.w(throwable = exception) { "Failed to create Nostr account." }
            credentialsStore.removeCredentialByNsec(nsec = privateKey.assureValidNsec())
            throw AccountCreationException(cause = exception)
        }.onSuccess {
            authRepository.loginWithNsec(nostrKey = privateKey)
        }
    }

    /**
     * Seeds the default note feeds for the freshly created account, both locally and remotely.
     *
     * Persisting locally before the account becomes active keeps the home top app bar populated the
     * moment we land on the main screen. Publishing them makes sure the subsequent user feeds fetch
     * finds a real feed list instead of an empty one, which would wipe what we just persisted.
     *
     * Passing an empty [preFetchedNoteFeeds] is not a request to persist nothing: it makes
     * [FeedsRepository.fetchAndPersistDefaultFeeds] fetch the defaults inline instead. That is the
     * fallback for when onboarding could not prefetch them in time.
     *
     * Failures are swallowed on purpose - feeds are cosmetic and must never fail account creation.
     */
    private suspend fun persistDefaultNoteFeeds(userId: String, preFetchedNoteFeeds: List<PrimalFeed>) {
        runCatching {
            feedsRepository.fetchAndPersistDefaultFeeds(
                userId = userId,
                specKind = FeedSpecKind.Notes,
                givenDefaultFeeds = preFetchedNoteFeeds,
            )
        }.onFailure { error ->
            Napier.w(throwable = error) { "Failed to persist default note feeds during account creation." }
        }
    }

    class AccountCreationException(cause: Throwable) : IOException(cause)
}
