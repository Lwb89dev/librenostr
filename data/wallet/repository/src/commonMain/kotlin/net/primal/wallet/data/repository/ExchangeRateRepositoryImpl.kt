package net.primal.wallet.data.repository

import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.rates.exchange.ExchangeRateRepository
import net.primal.wallet.data.remote.api.PrimalWalletApi

internal class ExchangeRateRepositoryImpl(
    @Suppress("UnusedPrivateProperty")
    private val dispatcherProvider: DispatcherProvider,
    @Suppress("UnusedPrivateProperty")
    private val primalWalletApi: PrimalWalletApi,
) : ExchangeRateRepository {

    @Suppress("UNUSED_PARAMETER")
    override suspend fun getExchangeRate(userId: String): Double {
        throw NetworkException("Primal wallet is disabled")
    }
}
