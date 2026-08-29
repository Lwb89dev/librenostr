package net.primal.android.auth.repository

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.android.user.repository.RelayRepository
import net.primal.android.user.repository.UserRepository
import net.primal.core.testing.CoroutinesTestRule
import net.primal.core.testing.FakeDataStore
import net.primal.domain.bookmarks.PublicBookmarksRepository
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.mutes.MutedItemRepository
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginHandlerTest {

    private val nsec = "nsec1p64ty2pgcj6k2c6v7u9dwu7aesle8v9qelnpgx4zrfa37av8f24qyftvle"

    private val expectedUserId by lazy {
        "88cc134b1a65f54ef48acc1df3665063d3ea45f04eab8af4646e561c5ae99079"
    }

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    private fun createLoginHandler(
        authRepository: AuthRepository = mockk(relaxed = true),
        userRepository: UserRepository = mockk(relaxed = true),
        mutedItemRepository: MutedItemRepository = mockk(relaxed = true),
        bookmarksRepository: PublicBookmarksRepository = mockk(relaxed = true),
        credentialsStore: CredentialsStore = mockk(relaxed = true),
        relayRepository: RelayRepository = mockk(relaxed = true),
    ): LoginHandler =
        LoginHandler(
            authRepository = authRepository,
            userRepository = userRepository,
            mutedItemRepository = mutedItemRepository,
            bookmarksRepository = bookmarksRepository,
            dispatchers = coroutinesTestRule.dispatcherProvider,
            credentialsStore = credentialsStore,
            relayRepository = relayRepository,
        )

    @Test
    fun login_callsLoginFromAuthRepository_withGivenKey() =
        runTest {
            val expectedKey = nsec
            val authRepository = mockk<AuthRepository>(relaxed = true)
            val loginHandler = createLoginHandler(authRepository = authRepository)
            loginHandler.login(
                nostrKey = expectedKey,
                credentialType = CredentialType.PrivateKey,
                authorizationEvent = null,
            )

            coVerify {
                authRepository.loginWithNsec(nostrKey = expectedKey)
            }
        }

    @Test
    fun login_callsFetchAndUpdateUserAccount() =
        runTest {
            val credentialsStore = mockk<CredentialsStore>(relaxed = true) {
                coEvery { saveNsec(any()) } returns expectedUserId
            }
            val userRepository = mockk<UserRepository>(relaxed = true)
            val loginHandler = createLoginHandler(
                userRepository = userRepository,
                credentialsStore = credentialsStore,
            )
            loginHandler.login(
                nostrKey = nsec,
                credentialType = CredentialType.PrivateKey,
                authorizationEvent = null,
            )

            coVerify {
                userRepository.fetchAndUpdateUserAccount(expectedUserId)
            }
        }

    @Test
    fun login_doesNotFetchAppSettingsFromPrimalCache() =
        runTest {
            val credentialsStore = mockk<CredentialsStore>(relaxed = true) {
                coEvery { saveNsec(any()) } returns expectedUserId
            }
            val loginHandler = createLoginHandler(credentialsStore = credentialsStore)

            loginHandler.login(
                nostrKey = nsec,
                credentialType = CredentialType.PrivateKey,
                authorizationEvent = null,
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { credentialsStore.saveNsec(nsec) }
        }

    @Test
    fun login_callsFetchAndPersistMuteList() =
        runTest {
            val credentialsStore = mockk<CredentialsStore>(relaxed = true) {
                coEvery { saveNsec(any()) } returns expectedUserId
            }
            val mutedItemRepository = mockk<MutedItemRepository>(relaxed = true)
            val loginHandler = createLoginHandler(
                mutedItemRepository = mutedItemRepository,
                credentialsStore = credentialsStore,
            )
            loginHandler.login(
                nostrKey = nsec,
                credentialType = CredentialType.PrivateKey,
                authorizationEvent = null,
            )

            coVerify {
                mutedItemRepository.fetchAndPersistMuteList(expectedUserId)
            }
        }

    @Test
    fun login_syncsUserRelaysOrBootstrap() =
        runTest {
            val credentialsStore = mockk<CredentialsStore>(relaxed = true) {
                coEvery { saveNsec(any()) } returns expectedUserId
            }
            val relayRepository = mockk<RelayRepository>(relaxed = true)
            val loginHandler = createLoginHandler(
                credentialsStore = credentialsStore,
                relayRepository = relayRepository,
            )
            loginHandler.login(
                nostrKey = nsec,
                credentialType = CredentialType.PrivateKey,
                authorizationEvent = null,
            )

            coVerify { relayRepository.ensureLocalBootstrapRelays(expectedUserId) }
        }

    @Test
    fun login_revertsLoginData_ifAnyOfApiCallsFail() =
        runTest {
            val credentialsPersistence = FakeDataStore(emptySet<Credential>())
            val credentialsStore = CredentialsStore(persistence = credentialsPersistence)

            val activeAccountPersistence = FakeDataStore(initialValue = "")
            val activeAccountStore = ActiveAccountStore(
                dispatchers = coroutinesTestRule.dispatcherProvider,
                accountsStore = mockk(relaxed = true),
                persistence = activeAccountPersistence,
            )

            val authRepository = AuthRepository(
                credentialsStore = credentialsStore,
                activeAccountStore = activeAccountStore,
                userRepository = mockk(relaxed = true),
                accountsStore = mockk(relaxed = true),
                connectionRepository = mockk(relaxed = true),
            )

            val userRepository = mockk<UserRepository>(relaxed = true) {
                coEvery { fetchAndUpdateUserAccount(any()) } throws NetworkException()
            }
            val loginHandler = createLoginHandler(
                authRepository = authRepository,
                userRepository = userRepository,
            )

            try {
                loginHandler.login(
                    nostrKey = nsec,
                    credentialType = CredentialType.PrivateKey,
                    authorizationEvent = null,
                )
            } catch (_: NetworkException) {
            }

            credentialsPersistence.latestData shouldBe emptyList()
            activeAccountPersistence.latestData shouldBe ""
        }

    @Test
    fun login_doesNotPrefetchNoteFeedsFromPrimalCache() =
        runTest {
            val credentialsStore = mockk<CredentialsStore>(relaxed = true) {
                coEvery { saveNsec(any()) } returns expectedUserId
            }
            val loginHandler = createLoginHandler(credentialsStore = credentialsStore)

            loginHandler.login(
                nostrKey = nsec,
                credentialType = CredentialType.PrivateKey,
                authorizationEvent = null,
            )

            coVerify(exactly = 1) { credentialsStore.saveNsec(nsec) }
        }

    @Test
    fun login_withExternalSigner_activatesAccountWithoutWaitingForNetwork() =
        runTest {
            val hexPubkey = expectedUserId
            val credentialsStore = mockk<CredentialsStore>(relaxed = true) {
                coEvery { saveExternalSignerNpub(any()) } returns expectedUserId
            }
            val userRepository = mockk<UserRepository>(relaxed = true)
            val authRepository = mockk<AuthRepository>(relaxed = true)
            val relayRepository = mockk<RelayRepository>(relaxed = true)
            val loginHandler = createLoginHandler(
                authRepository = authRepository,
                userRepository = userRepository,
                credentialsStore = credentialsStore,
                relayRepository = relayRepository,
            )

            loginHandler.login(
                nostrKey = hexPubkey,
                credentialType = CredentialType.ExternalSigner,
                authorizationEvent = null,
            )

            coVerify(exactly = 1) { authRepository.loginWithExternalSignerNpub(npub = hexPubkey) }
            coVerify(exactly = 1) { relayRepository.ensureLocalBootstrapRelays(expectedUserId) }
            coVerify(exactly = 0) { userRepository.fetchAndUpdateUserAccount(any()) }
        }
}
