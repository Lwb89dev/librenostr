package net.primal.wallet.data.service.factory

import net.primal.core.lightning.LightningPayHelper
import net.primal.domain.events.EventRepository
import net.primal.domain.wallet.SparkWalletManager
import net.primal.domain.wallet.Wallet
import net.primal.wallet.data.service.NostrWalletServiceImpl
import net.primal.wallet.data.service.DisabledPrimalWalletService
import net.primal.wallet.data.service.DisabledSparkWalletService
import net.primal.wallet.data.service.SparkSdkEventProvider
import net.primal.wallet.data.service.SparkWalletServiceImpl
import net.primal.wallet.data.service.WalletService
import net.primal.wallet.data.spark.BreezSdkInstanceManager

internal class WalletServiceFactoryImpl(
    private val primalWalletService: WalletService<Wallet.Primal>,
    private val nostrWalletService: WalletService<Wallet.NWC>,
    private val sparkWalletService: WalletService<Wallet.Spark>,
) : WalletServiceFactory {

    @Suppress("UNCHECKED_CAST")
    override fun getServiceForWallet(wallet: Wallet): WalletService<Wallet> {
        return when (wallet) {
            is Wallet.Primal -> primalWalletService
            is Wallet.NWC -> nostrWalletService
            is Wallet.Spark -> sparkWalletService
        } as WalletService<Wallet>
    }

    companion object {
        fun createPrimalWalletService() = DisabledPrimalWalletService

        fun createNostrWalletService(eventRepository: EventRepository, lightningPayHelper: LightningPayHelper) =
            NostrWalletServiceImpl(
                eventRepository = eventRepository,
                lightningPayHelper = lightningPayHelper,
            )

        fun createSparkWalletService(
            breezSdkInstanceManager: BreezSdkInstanceManager,
            eventRepository: EventRepository,
            sparkWalletManager: SparkWalletManager,
            sparkSdkEventProvider: SparkSdkEventProvider,
        ) = DisabledSparkWalletService
    }
}
