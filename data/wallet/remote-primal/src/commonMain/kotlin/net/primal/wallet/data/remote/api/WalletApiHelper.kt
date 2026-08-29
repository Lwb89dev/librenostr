package net.primal.wallet.data.remote.api

import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.cryptography.NostrEventSignatureHandler
import net.primal.wallet.data.remote.WalletOperationVerb
import net.primal.wallet.data.remote.model.WalletOperationRequestBody

internal suspend fun buildWalletOptionsJson(
    userId: String,
    walletVerb: WalletOperationVerb,
    @Suppress("UNUSED_PARAMETER") requestBody: WalletOperationRequestBody,
    @Suppress("UNUSED_PARAMETER") signatureHandler: NostrEventSignatureHandler,
): String {
    throw NetworkException("Primal wallet is disabled ($walletVerb for $userId)")
}
