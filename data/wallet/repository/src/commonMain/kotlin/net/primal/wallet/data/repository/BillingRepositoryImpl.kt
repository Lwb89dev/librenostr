package net.primal.wallet.data.repository

import net.primal.domain.billing.BillingRepository
import net.primal.domain.billing.InAppPurchaseSatsQuote

internal object BillingRepositoryImpl : BillingRepository {

    override suspend fun getInAppPurchaseMinSatsQuote(
        userId: String,
        region: String,
        productId: String,
        previousQuoteId: String?,
    ): InAppPurchaseSatsQuote {
        error("Centralized in-app purchases are disabled; use NWC or an external wallet.")
    }

    override suspend fun confirmInAppPurchase(
        userId: String,
        quoteId: String,
        purchaseToken: String,
    ) {
        error("Centralized in-app purchases are disabled; use NWC or an external wallet.")
    }
}
