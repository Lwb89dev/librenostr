package net.primal.wallet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.primal.core.utils.Result
import net.primal.domain.wallet.SparkWalletManager
import net.primal.domain.wallet.UnclaimedDepositEvent
import net.primal.wallet.data.service.SparkSdkEventProvider
import net.primal.wallet.data.service.model.SparkSdkEvent

/**
 * The Spark backend was served by the Breez Spark SDK, which is no longer bundled.
 *
 * `WalletServiceFactoryImpl.createSparkWalletService` already returned
 * `DisabledSparkWalletService` and discarded every SDK-backed collaborator it was
 * handed, so the backend had no runtime behaviour left. What the SDK still cost was
 * 17.8 MB of native code per ABI and, through
 * `androidx.credentials:credentials-play-services-auth`, the whole Google Play
 * Services auth/fido stack in a build that ships without Google services.
 *
 * `Wallet.Spark` itself stays: rows exist in the wallet database and the Room
 * migrations must keep resolving. NWC remains the only wallet transport.
 */
internal object DisabledSparkWalletManager : SparkWalletManager, SparkSdkEventProvider {

    override val unclaimedDeposits: Flow<UnclaimedDepositEvent> = emptyFlow()

    override val balanceChanged: Flow<String> = emptyFlow()

    override val sdkEvents: Flow<SparkSdkEvent> = emptyFlow()

    override suspend fun initializeWallet(seedWords: String): Result<String> = disabled()

    override suspend fun disconnectWallet(walletId: String): Result<Unit> = Result.success(Unit)

    override suspend fun hasInstance(walletId: String): Boolean = false

    private fun <T> disabled(): Result<T> =
        Result.failure(IllegalStateException("The Spark wallet backend is not bundled in this build."))
}
