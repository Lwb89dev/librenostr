package net.primal.data.repository.broadcast

import net.primal.core.utils.Result
import net.primal.domain.premium.BroadcastingStatus
import net.primal.domain.premium.PremiumBroadcastRepository

/**
 * Content rebroadcast was a Primal membership service, not a Nostr protocol
 * feature. Keep the repository contract for UI compatibility, but make it a
 * local no-op so the app never signs requests for or contacts that service.
 */
class PremiumBroadcastRepositoryImpl : PremiumBroadcastRepository {
    override suspend fun fetchContentStats(userId: String): Result<Map<Int, Long>> =
        Result.success(emptyMap())

    override suspend fun startBroadcast(userId: String, kinds: List<Int>?): Result<Unit> =
        Result.failure(UnsupportedOperationException("Content rebroadcast is not available in LibreNostr"))

    override suspend fun cancelBroadcast(userId: String): Result<Unit> = Result.success(Unit)

    override suspend fun fetchBroadcastStatus(userId: String): Result<BroadcastingStatus> =
        Result.success(
            BroadcastingStatus(
                running = false,
                kinds = null,
                status = "unavailable",
                progress = 0f,
            ),
        )
}
