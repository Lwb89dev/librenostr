package net.primal.domain.profile

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import net.primal.core.utils.Result
import net.primal.domain.common.UserProfileSearchItem
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.ReportType
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.publisher.NostrPublishException

interface ProfileRepository {
    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchProfileId(primalName: String): String?

    suspend fun findProfileDataOrNull(profileId: String): ProfileData?

    suspend fun findProfileDataByLightningAddress(lightningAddress: String): ProfileData?

    suspend fun findProfileData(profileIds: List<String>): List<ProfileData>

    suspend fun findProfileStats(profileIds: List<String>): List<ProfileStats>

    /**
     * Fetches [profileId]'s follower/following/notes/zap counters and persists them, so
     * [observeProfileStats] has something to emit. Nothing else populates these on opening a
     * profile that hasn't separately shown up in, say, a notification — without an explicit
     * fetch they stay permanently unset rather than merely stale. Failures are swallowed by the
     * implementation: a profile screen should still show whatever else loaded even if this one
     * counter fetch fails.
     */
    suspend fun fetchAndCacheProfileStats(profileId: String)

    fun observeProfileData(profileId: String): Flow<ProfileData>
    fun observeProfileData(profileIds: List<String>): Flow<List<ProfileData>>
    fun observeProfileStats(profileId: String): Flow<ProfileStats?>

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchProfile(profileId: String): ProfileData?

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchProfiles(profileIds: List<String>): List<ProfileData>

    suspend fun fetchMissingProfiles(profileIds: List<String>): Result<List<ProfileData>>

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchUserProfileFollowedBy(
        profileId: String,
        userId: String,
        limit: Int,
    ): List<ProfileData>

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun isUserFollowing(userId: String, targetUserId: String): Boolean

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchFollowers(profileId: String): List<UserProfileSearchItem>

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchFollowing(profileId: String): List<UserProfileSearchItem>

    @Throws(
        NostrPublishException::class,
        SignatureException::class,
        CancellationException::class,
    )
    suspend fun reportAbuse(
        userId: String,
        reportType: ReportType,
        profileId: String,
        eventId: String? = null,
        articleId: String? = null,
    )
}
