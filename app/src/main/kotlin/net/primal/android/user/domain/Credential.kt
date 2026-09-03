package net.primal.android.user.domain

import kotlinx.serialization.Serializable

@Serializable
data class Credential(
    val nsec: String?,
    val npub: String,
    val type: CredentialType = CredentialType.PrivateKey,
    /** The bunker's own pubkey (hex) — [RemoteSigner] only, the channel every request is sent to. */
    val remoteSignerPubkey: String? = null,
    /** Relays the bunker connection was made on — [RemoteSigner] only. */
    val remoteSignerRelays: List<String> = emptyList(),
    /** The optional NIP-46 `secret` this bunker connection was authorized with — [RemoteSigner] only. */
    val remoteSignerSecret: String? = null,
    /**
     * This device's throwaway keypair for the NIP-46 channel (hex) — [RemoteSigner] only, never
     * the account's own key.
     */
    val remoteSignerClientPrivateKey: String? = null,
)

enum class CredentialType {
    InternalSigner,
    ExternalSigner,
    PrivateKey,
    PublicKey,
    RemoteSigner,
}
