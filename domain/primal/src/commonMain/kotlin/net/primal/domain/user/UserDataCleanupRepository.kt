package net.primal.domain.user

interface UserDataCleanupRepository {
    /**
     * Deletes [userId]'s own rows. [clearSharedCaches] additionally drops the coordinator's
     * follow-list cache and the hot event layer, both process-wide and shared by every logged-in
     * account — pass `true` only when [userId] was the last account signed in, otherwise it
     * discards cache that other still-logged-in accounts are relying on for nothing gained.
     */
    suspend fun clearUserData(userId: String, clearSharedCaches: Boolean)
}
