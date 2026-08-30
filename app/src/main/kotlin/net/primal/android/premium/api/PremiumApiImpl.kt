package net.primal.android.premium.api

import javax.inject.Inject
import net.primal.android.premium.api.model.CancelMembershipRequest
import net.primal.android.premium.api.model.LegendLeaderboardOrderBy
import net.primal.android.premium.api.model.LegendLeaderboardResponse
import net.primal.android.premium.api.model.LegendPaymentInstructionsResponse
import net.primal.android.premium.api.model.MembershipStatusResponse
import net.primal.android.premium.api.model.NameAvailableResponse
import net.primal.android.premium.api.model.PremiumLeaderboardOrderBy
import net.primal.android.premium.api.model.PremiumLeaderboardResponse
import net.primal.android.premium.api.model.PurchaseMembershipRequest
import net.primal.android.premium.api.model.UpdatePrimalLegendProfileRequest
import net.primal.android.premium.domain.PremiumPurchaseOrder
import net.primal.domain.nostr.NostrEvent

/**
 * Premium is a Primal-hosted product and is intentionally unavailable in
 * LibreNostr. This compatibility implementation keeps old screens from
 * requiring a cache/wallet client while returning empty local results.
 */
class PremiumApiImpl @Inject constructor() : PremiumApi {
    override suspend fun isPrimalNameAvailable(name: String) = NameAvailableResponse(available = false)

    override suspend fun changePrimalName(userId: String, name: String) = NameAvailableResponse(available = false)

    override suspend fun getPremiumMembershipStatus(userId: String): MembershipStatusResponse? = null

    override suspend fun purchaseMembership(userId: String, body: PurchaseMembershipRequest) = Unit

    override suspend fun getPrimalLegendPaymentInstructions(
        userId: String,
        primalName: String,
        onChain: Boolean,
        amountUsd: String?,
    ): LegendPaymentInstructionsResponse = LegendPaymentInstructionsResponse(
        membershipQuoteId = "",
        amountUsd = "0",
        amountBtc = "0",
        qrCode = "",
    )

    override suspend fun getMembershipProducts() = Unit

    override suspend fun cancelMembership(userId: String, body: CancelMembershipRequest) = Unit

    override suspend fun shouldShowSupportUs() = false

    override suspend fun getOrdersHistory(userId: String): List<PremiumPurchaseOrder> = emptyList()

    override suspend fun updateLegendProfile(userId: String, updateProfileRequest: UpdatePrimalLegendProfileRequest) = Unit

    override suspend fun getLegendLeaderboard(orderBy: LegendLeaderboardOrderBy, limit: Int) =
        LegendLeaderboardResponse(
            nostrEvents = emptyList(),
            primalEvents = emptyList(),
            orderedLegendLeaderboardEvent = null,
            primalPremiumInfoEvents = emptyList(),
            primalLegendProfiles = emptyList(),
            primalUsernames = emptyList(),
            cdnResources = emptyList(),
            userFollowersCounts = emptyList(),
            userScores = emptyList(),
            profileMetadatas = emptyList(),
        )

    override suspend fun getPremiumLeaderboard(
        since: Long?,
        until: Long?,
        orderBy: PremiumLeaderboardOrderBy,
        limit: Int,
    ) = PremiumLeaderboardResponse(
        nostrEvents = emptyList(),
        primalEvents = emptyList(),
        orderedPremiumLeaderboardEvent = null,
        primalPremiumInfoEvents = emptyList(),
        primalUsernames = emptyList(),
        cdnResources = emptyList(),
        userFollowersCounts = emptyList(),
        userScores = emptyList(),
        profileMetadatas = emptyList(),
    )

    override suspend fun getRecoveryContactsList(userId: String): List<NostrEvent> = emptyList()
}
