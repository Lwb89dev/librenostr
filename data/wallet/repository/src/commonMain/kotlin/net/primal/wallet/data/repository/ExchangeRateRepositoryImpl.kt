package net.primal.wallet.data.repository

import net.primal.domain.common.exception.NetworkException
import net.primal.domain.rates.exchange.ExchangeRateRepository

internal object ExchangeRateRepositoryImpl : ExchangeRateRepository {

    @Suppress("UNUSED_PARAMETER")
    override suspend fun getExchangeRate(userId: String): Double {
        throw NetworkException("Legacy wallet transport is disabled")
    }
}
