package net.primal.android.wallet.repository

import io.github.aakira.napier.Napier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.primal.domain.rates.exchange.ExchangeRateRepository

@Singleton
class ExchangeRateHandler @Inject constructor(
    @Suppress("UnusedPrivateProperty")
    private val exchangeRateRepository: ExchangeRateRepository,
) {

    private val _state = MutableStateFlow(value = 0.00)
    val usdExchangeRate = _state.asStateFlow()

    suspend fun updateExchangeRate(userId: String) {
        Napier.d { "Skipping legacy wallet exchange-rate fetch for $userId" }
    }
}

fun Double?.isValidExchangeRate() = this != null && this > 0
