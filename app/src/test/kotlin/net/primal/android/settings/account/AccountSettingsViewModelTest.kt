package net.primal.android.settings.account

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.android.user.domain.UserAccount
import net.primal.core.testing.CoroutinesTestRule
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModelTest {

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    private val pubkeyHex = "88cc134b1a65f54ef48acc1df3665063d3ea45f04eab8af4646e561c5ae99079"
    private val npub = pubkeyHex.hexToNpubHrp()

    @Test
    fun emptyAccount_doesNotThrow() =
        runTest {
            val viewModel = AccountSettingsViewModel(
                credentialsStore = mockk(relaxed = true),
                activeAccountStore = mockk {
                    every { activeUserAccount } returns flowOf(UserAccount.EMPTY)
                    every { activeUserId() } returns ""
                },
            )

            advanceUntilIdle()

            viewModel.state.value.npub shouldBe ""
            viewModel.state.value.nsec shouldBe null
        }

    @Test
    fun amberCredential_showsNpubWithoutNsec() =
        runTest {
            val credential = Credential(nsec = null, npub = npub, type = CredentialType.ExternalSigner)
            val viewModel = AccountSettingsViewModel(
                credentialsStore = mockk {
                    every { find(npub) } returns credential
                },
                activeAccountStore = mockk {
                    every { activeUserAccount } returns flowOf(UserAccount.buildLocal(pubkeyHex))
                    every { activeUserId() } returns pubkeyHex
                },
            )

            advanceUntilIdle()

            viewModel.state.value.npub shouldBe npub
            viewModel.state.value.nsec shouldBe null
        }

    @Test
    fun emptyAccountWithActiveUserId_resolvesAmberNpub() =
        runTest {
            val credential = Credential(nsec = null, npub = npub, type = CredentialType.ExternalSigner)
            val viewModel = AccountSettingsViewModel(
                credentialsStore = mockk {
                    every { find(npub) } returns credential
                },
                activeAccountStore = mockk {
                    every { activeUserAccount } returns flowOf(UserAccount.EMPTY)
                    every { activeUserId() } returns pubkeyHex
                },
            )

            advanceUntilIdle()

            viewModel.state.value.npub shouldBe npub
            viewModel.state.value.nsec shouldBe null
        }
}
