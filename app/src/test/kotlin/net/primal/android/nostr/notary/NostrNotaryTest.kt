package net.primal.android.nostr.notary

import android.content.ContentResolver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.core.nips.encryption.service.NostrEncryptionService
import net.primal.core.testing.CoroutinesTestRule
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignResult
import net.primal.domain.nostr.cryptography.SigningKeyNotFoundException
import net.primal.domain.nostr.cryptography.SigningRejectedException
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import org.junit.Rule
import org.junit.Test

/**
 * A [CredentialType.RemoteSigner] credential needs a bunker pubkey, at least one relay and a
 * client keypair before it can be used at all — those are the only way [NostrNotary] knows where
 * and as whom to ask a bunker to sign. A credential missing any of them must be rejected before
 * any network attempt, not fall through to [NostrNotary]'s local-nsec path (which would otherwise
 * throw the same [SigningKeyNotFoundException] for the unrelated reason that a remote-signer
 * credential's `nsec` is always null, masking a real routing bug as an identical-looking failure).
 */
@ExperimentalCoroutinesApi
class NostrNotaryTest {

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    private val userId = "a".repeat(64)

    private fun notary(credentialsStore: CredentialsStore) =
        NostrNotary(
            dispatchers = coroutinesTestRule.dispatcherProvider,
            contentResolver = mockk<ContentResolver>(relaxed = true),
            credentialsStore = credentialsStore,
            nostrEncryptionService = mockk<NostrEncryptionService>(relaxed = true),
        )

    @Test
    fun `a remote-signer credential missing its bunker pubkey is rejected before any network attempt`() =
        runTest {
            val credentialsStore = fakeCredentialsStore(
                Credential(
                    nsec = null,
                    npub = userId.hexToNpubHrp(),
                    type = CredentialType.RemoteSigner,
                    remoteSignerPubkey = null,
                    remoteSignerRelays = listOf("wss://relay.example.com"),
                    remoteSignerClientPrivateKey = "client-privkey-hex",
                ),
            )

            val result = notary(credentialsStore).signNostrEvent(unsignedEvent())

            (result as SignResult.Rejected).error::class shouldBe SigningKeyNotFoundException::class
        }

    @Test
    fun `a remote-signer credential missing relays is rejected before any network attempt`() =
        runTest {
            val credentialsStore = fakeCredentialsStore(
                Credential(
                    nsec = null,
                    npub = userId.hexToNpubHrp(),
                    type = CredentialType.RemoteSigner,
                    remoteSignerPubkey = "bunker-pubkey",
                    remoteSignerRelays = emptyList(),
                    remoteSignerClientPrivateKey = "client-privkey-hex",
                ),
            )

            val result = notary(credentialsStore).signNostrEvent(unsignedEvent())

            (result as SignResult.Rejected).error::class shouldBe SigningKeyNotFoundException::class
        }

    @Test
    fun `a remote-signer credential missing its client keypair is rejected before any network attempt`() =
        runTest {
            val credentialsStore = fakeCredentialsStore(
                Credential(
                    nsec = null,
                    npub = userId.hexToNpubHrp(),
                    type = CredentialType.RemoteSigner,
                    remoteSignerPubkey = "bunker-pubkey",
                    remoteSignerRelays = listOf("wss://relay.example.com"),
                    remoteSignerClientPrivateKey = null,
                ),
            )

            val result = notary(credentialsStore).signNostrEvent(unsignedEvent())

            (result as SignResult.Rejected).error::class shouldBe SigningKeyNotFoundException::class
        }

    @Test
    fun `a remote-signer credential is never asked to sign a leftover kind that isn't in the signable set`() =
        runTest {
            // NwcRequest is signed with the wallet connection's own secret, never the account's
            // identity — it must never reach a bunker at all, the same way it never reached Amber.
            val credentialsStore = fakeCredentialsStore(
                Credential(
                    nsec = null,
                    npub = userId.hexToNpubHrp(),
                    type = CredentialType.RemoteSigner,
                    remoteSignerPubkey = "bunker-pubkey",
                    remoteSignerRelays = listOf("wss://relay.example.com"),
                    remoteSignerClientPrivateKey = "client-privkey-hex",
                ),
            )

            val result = notary(credentialsStore)
                .signNostrEvent(unsignedEvent(kind = NostrEventKind.NwcRequest.value))

            (result as SignResult.Rejected).error::class shouldBe SigningRejectedException::class
            verify(exactly = 0) { credentialsStore.findOrThrow(any()) }
        }

    private fun unsignedEvent(kind: Int = 1) =
        NostrUnsignedEvent(pubKey = userId, kind = kind, content = "hello")

    private fun fakeCredentialsStore(credential: Credential): CredentialsStore =
        mockk<CredentialsStore>(relaxed = true) {
            every { isExternalSignerCredential(any()) } returns false
            every { isRemoteSignerCredential(any()) } returns true
            every { findOrThrow(any()) } returns credential
        }
}
