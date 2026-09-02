package net.primal.data.repository.profile

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.mappers.local.asProfileDataDO
import net.primal.data.repository.mappers.local.asProfileStatsDO
import net.primal.data.repository.mappers.remote.asProfileDataPOFromRelay
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.common.UserProfileSearchItem
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.ReportType
import net.primal.domain.nostr.asEventIdTag
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.asReplaceableEventTag
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.profile.Nip05VerificationService
import net.primal.domain.profile.ProfileData
import net.primal.domain.profile.ProfileRepository
import net.primal.domain.profile.ProfileStats
import net.primal.domain.publisher.PrimalPublisher
import net.primal.domain.nostr.utils.isValidHex
import kotlinx.serialization.json.jsonPrimitive

internal class ProfileRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val primalPublisher: PrimalPublisher,
    private val nip05VerificationService: Nip05VerificationService? = null,
    private val relayEventQuerier: RelayEventQuerier? = null,
    private val fetchCoordinator: FetchCoordinator,
) : ProfileRepository {

    companion object {
        private const val FOLLOW_LIST_QUERY_LIMIT = 20
        private const val FOLLOWERS_QUERY_LIMIT = 200
        private const val PROFILE_NAME_QUERY_LIMIT = 500
    }

    override suspend fun fetchProfileId(primalName: String): String? =
        withContext(dispatcherProvider.io()) {
            val normalizedName = primalName.removePrefix("@").substringBefore("@").trim()
            if (normalizedName.isBlank()) return@withContext null

            val querier = relayEventQuerier ?: return@withContext null
            runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        limit = PROFILE_NAME_QUERY_LIMIT,
                    ),
                )
                    .latestMetadataByPubkey()
                    .map { it.asProfileDataPOFromRelay() }
                    .firstOrNull { profile ->
                        profile.handle.equals(normalizedName, ignoreCase = true) ||
                            profile.internetIdentifier
                                ?.substringBefore("@")
                                ?.equals(normalizedName, ignoreCase = true) == true
                    }
                    ?.ownerId
            }.getOrNull()
        }

    override suspend fun findProfileDataOrNull(profileId: String) =
        withContext(dispatcherProvider.io()) {
            val profile = database.profiles().findProfileData(profileId = profileId)
            profile?.asProfileDataDO()
        }

    override suspend fun findProfileDataByLightningAddress(lightningAddress: String) =
        withContext(dispatcherProvider.io()) {
            val profile = database.profiles()
                .findProfileDataByLightningAddress(lightningAddress = lightningAddress)
            profile?.asProfileDataDO()
        }

    override suspend fun findProfileStats(profileIds: List<String>): List<ProfileStats> =
        withContext(dispatcherProvider.io()) {
            database.profileStats().findProfileStats(profileIds = profileIds)
                .map { it.asProfileStatsDO() }
        }

    override suspend fun findProfileData(profileIds: List<String>) =
        withContext(dispatcherProvider.io()) {
            val profiles = database.profiles().findProfileData(profileIds = profileIds)
            profiles.map { it.asProfileDataDO() }
        }

    override fun observeProfileData(profileId: String) =
        database.profiles().observeProfileData(profileId = profileId)
            .filterNotNull()
            .map { it.asProfileDataDO() }

    override fun observeProfileData(profileIds: List<String>) =
        database.profiles().observeProfilesData(profileIds = profileIds)
            .map { profiles -> profiles.map { it.asProfileDataDO() } }

    override fun observeProfileStats(profileId: String) =
        database.profileStats().observeProfileStats(profileId = profileId)
            .filterNotNull()
            .map { it.asProfileStatsDO() }

    override suspend fun fetchMissingProfiles(profileIds: List<String>): Result<List<ProfileData>> =
        withContext(dispatcherProvider.io()) {
            val existingProfileIds = database.profiles().findExistingProfileIds(profileIds = profileIds)
            val missingProfileIds = profileIds.toSet() - existingProfileIds.toSet()

            if (missingProfileIds.isNotEmpty()) {
                runCatching { fetchProfiles(profileIds = missingProfileIds.toList()) }
            } else {
                Result.success(emptyList())
            }
        }

    override suspend fun fetchUserProfileFollowedBy(
        profileId: String,
        userId: String,
        limit: Int,
    ): List<ProfileData> =
        withContext(dispatcherProvider.io()) {
            val profileFollowing = latestFollowList(profileId)?.followingPubkeys().orEmpty()
            val userFollowing = latestFollowList(userId)?.followingPubkeys().orEmpty()
            fetchProfiles((profileFollowing intersect userFollowing).take(limit).toList())
        }

    override suspend fun fetchProfile(profileId: String) =
        withContext(dispatcherProvider.io()) {
            val local = database.profiles().findProfileData(profileId = profileId)
            if (local != null) return@withContext local.asProfileDataDO()
            val fromRelays = persistRelayMetadata(listOf(profileId))
            val relayProfile = fromRelays.firstOrNull()
            if (relayProfile != null) {
                verifyNip05IfPresent(profileId, relayProfile)
            }
            relayProfile
        }

    override suspend fun fetchProfiles(profileIds: List<String>): List<ProfileData> =
        withContext(dispatcherProvider.io()) {
            val fromRelays = persistRelayMetadata(profileIds)
            val foundIds = fromRelays.mapTo(mutableSetOf()) { it.profileId }
            val localProfiles = database.profiles()
                .findProfileData(profileIds.filterNot(foundIds::contains))
                .map { it.asProfileDataDO() }
            fromRelays + localProfiles
        }

    private suspend fun persistRelayMetadata(profileIds: List<String>): List<ProfileData> {
        val events = queryRelayMetadata(profileIds)
        if (events.isEmpty()) return emptyList()
        val profiles = events.latestMetadataByPubkey().map { it.asProfileDataPOFromRelay() }
        database.profiles().insertOrUpdateAll(data = profiles)
        return profiles.map { it.asProfileDataDO() }
    }

    /**
     * Profile metadata, coalesced per author with whatever else is asking for it.
     *
     * This screen wants what the relays hold now, so it takes no part in the event cache's
     * once-per-session claim: a display name that changed while the app was open has to show up
     * here. Sharing a request already in flight costs nothing in freshness, which is the whole
     * reason the coordinator draws that line where it does.
     */
    private suspend fun queryRelayMetadata(profileIds: List<String>): List<NostrEvent> {
        val querier = relayEventQuerier ?: return emptyList()
        if (profileIds.isEmpty()) return emptyList()
        return fetchCoordinator.fetchMetadata(querier = querier, pubkeys = profileIds)
    }

    private suspend fun verifyNip05IfPresent(profileId: String, profile: ProfileData?) {
        val identifier = profile?.internetIdentifier ?: return
        nip05VerificationService?.verifyIfNeeded(
            pubkey = profileId,
            internetIdentifier = identifier,
        )
    }

    override suspend fun fetchFollowers(profileId: String): List<UserProfileSearchItem> =
        withContext(dispatcherProvider.io()) {
            val querier = relayEventQuerier ?: return@withContext emptyList()
            val followerIds = runCatching {
                querier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.FollowList.value),
                        pubkeyTags = listOf(profileId),
                        limit = FOLLOWERS_QUERY_LIMIT,
                    ),
                )
            }.getOrDefault(emptyList())
                .asSequence()
                .map { it.pubKey }
                .filter { it.isValidHex() && it != profileId }
                .distinct()
                .toList()

            fetchProfiles(followerIds).map { UserProfileSearchItem(metadata = it) }
        }

    override suspend fun fetchFollowing(profileId: String): List<UserProfileSearchItem> =
        withContext(dispatcherProvider.io()) {
            latestFollowList(profileId)
                ?.followingPubkeys()
                ?.let { fetchProfiles(it.toList()) }
                .orEmpty()
                .map { UserProfileSearchItem(metadata = it) }
        }

    override suspend fun reportAbuse(
        userId: String,
        reportType: ReportType,
        profileId: String,
        eventId: String?,
        articleId: String?,
    ) {
        withContext(dispatcherProvider.io()) {
            val profileTag = profileId.asPubkeyTag(optional = if (eventId == null) reportType.id else null)
            val eventTag = eventId?.asEventIdTag(marker = reportType.id)
            val articleTag = articleId?.let {
                "${NostrEventKind.LongFormContent.value}:$profileId:$articleId".asReplaceableEventTag()
            }

            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    content = "",
                    kind = NostrEventKind.Reporting.value,
                    tags = listOfNotNull(profileTag, eventTag, articleTag),
                ),
            )
        }
    }

    override suspend fun isUserFollowing(userId: String, targetUserId: String) =
        latestFollowList(userId)?.followingPubkeys()?.contains(targetUserId) == true

    private suspend fun latestFollowList(profileId: String): NostrEvent? {
        val querier = relayEventQuerier ?: return null
        return runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.FollowList.value),
                    authors = listOf(profileId),
                    limit = FOLLOW_LIST_QUERY_LIMIT,
                ),
            )
        }.getOrDefault(emptyList()).maxByOrNull { it.createdAt }
    }

    private fun NostrEvent.followingPubkeys(): Set<String> = tags.mapNotNull { tag ->
        if (tag.getOrNull(0)?.jsonPrimitive?.content != "p") return@mapNotNull null
        tag.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isValidHex() }
    }.toSet()

}
