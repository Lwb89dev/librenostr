package net.primal.wallet.data.service

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.primal.core.utils.Result
import net.primal.domain.rates.fees.OnChainTransactionFeeTier
import net.primal.domain.wallet.LightningPaymentResult
import net.primal.domain.wallet.LnInvoiceCreateRequest
import net.primal.domain.wallet.LnInvoiceCreateResult
import net.primal.domain.wallet.OnChainAddressResult
import net.primal.domain.wallet.PayResult
import net.primal.domain.wallet.TransactionsPage
import net.primal.domain.wallet.TransactionsRequest
import net.primal.domain.wallet.TxRequest
import net.primal.domain.wallet.Wallet
import net.primal.domain.wallet.exception.WalletPaymentException
import net.primal.domain.wallet.model.WalletBalanceResult

/**
 * Fail-closed adapter for legacy Primal wallets.
 *
 * LibreNostr never sends wallet operations to a centralized service. Users must
 * configure NWC or pay a generated invoice through an external Lightning wallet.
 */
internal object DisabledPrimalWalletService : WalletService<Wallet.Primal> {
    private fun disabled(): Nothing = throw WalletPaymentException.OperationNotSupported(
        operation = "legacy centralized wallet",
    )

    override suspend fun fetchWalletBalance(wallet: Wallet.Primal): Result<WalletBalanceResult> = disabled()

    override suspend fun subscribeToWalletBalance(wallet: Wallet.Primal): Flow<WalletBalanceResult> = emptyFlow()

    override suspend fun fetchTransactions(
        wallet: Wallet.Primal,
        request: TransactionsRequest,
    ): Result<TransactionsPage> = disabled()

    override suspend fun createLightningInvoice(
        wallet: Wallet.Primal,
        request: LnInvoiceCreateRequest,
    ): Result<LnInvoiceCreateResult> = disabled()

    override suspend fun createOnChainAddress(wallet: Wallet.Primal): Result<OnChainAddressResult> = disabled()

    override suspend fun pay(wallet: Wallet.Primal, request: TxRequest): Result<PayResult> = disabled()

    override suspend fun fetchMiningFees(
        wallet: Wallet.Primal,
        onChainAddress: String,
        amountInBtc: String,
    ): Result<List<OnChainTransactionFeeTier>> = disabled()

    override suspend fun awaitLightningPayment(
        wallet: Wallet.Primal,
        invoice: String?,
        timeout: Duration,
    ): Result<LightningPaymentResult> = disabled()
}

/** Spark/Breez is also not an approved payment backend in the relay-only app. */
internal object DisabledSparkWalletService : WalletService<Wallet.Spark> {
    private fun disabled(): Nothing = throw WalletPaymentException.OperationNotSupported(
        operation = "embedded wallet transport",
    )

    override suspend fun fetchWalletBalance(wallet: Wallet.Spark): Result<WalletBalanceResult> = disabled()
    override suspend fun subscribeToWalletBalance(wallet: Wallet.Spark): Flow<WalletBalanceResult> = emptyFlow()
    override suspend fun fetchTransactions(wallet: Wallet.Spark, request: TransactionsRequest): Result<TransactionsPage> = disabled()
    override suspend fun createLightningInvoice(wallet: Wallet.Spark, request: LnInvoiceCreateRequest): Result<LnInvoiceCreateResult> = disabled()
    override suspend fun createOnChainAddress(wallet: Wallet.Spark): Result<OnChainAddressResult> = disabled()
    override suspend fun pay(wallet: Wallet.Spark, request: TxRequest): Result<PayResult> = disabled()
    override suspend fun fetchMiningFees(wallet: Wallet.Spark, onChainAddress: String, amountInBtc: String): Result<List<OnChainTransactionFeeTier>> = disabled()
    override suspend fun awaitLightningPayment(wallet: Wallet.Spark, invoice: String?, timeout: Duration): Result<LightningPaymentResult> = disabled()
}
