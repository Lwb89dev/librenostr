package net.primal.wallet.data.repository

import net.primal.domain.connections.primal.PrimalWalletNwcRepository
import net.primal.domain.connections.primal.model.PrimalNwcConnection
import net.primal.domain.connections.primal.model.PrimalNwcConnectionInfo

/** Centralized NWC provisioning was a legacy wallet feature and is disabled. */
internal object DisabledPrimalWalletNwcRepository : PrimalWalletNwcRepository {
    override suspend fun getConnections(userId: String): List<PrimalNwcConnectionInfo> = emptyList()

    override suspend fun createNewWalletConnection(
        userId: String,
        appName: String,
        dailyBudget: String?,
    ): PrimalNwcConnection = error("Centralized NWC provisioning is disabled; connect an NWC URI directly.")

    override suspend fun revokeConnection(userId: String, nwcPubkey: String) = Unit
}
