package net.primal.data.repository.explore

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.profiles.ProfileData as LocalProfileData
import net.primal.data.local.dao.profiles.ProfileDataDao
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Mention/tag search used to be relay-only: typing "@Gi" surfaced a cached follow named "Gigi"
 * only if that same relay round-trip happened to find it again. A profile already known locally
 * (followed, seen in the feed, previously fetched) should match instantly by name prefix, without
 * waiting on a relay at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreRepositorySearchUsersTest {

    @Test
    fun `a blank query is not searched anywhere`() =
        runTest {
            val profilesDao = mockk<ProfileDataDao>()
            val querier = RecordingQuerier()

            val result = repository(profilesDao = profilesDao, querier = querier).searchUsers(query = "  ", limit = 10)

            assertTrue(result.isEmpty())
            assertTrue(querier.metadataFilters.isEmpty())
        }

    @Test
    fun `local matches filling the limit are returned without asking a relay`() =
        runTest {
            val profilesDao = mockk<ProfileDataDao> {
                coEvery { findProfilesByPrefix(prefix = "gi", limit = 2) } returns
                    listOf(
                        localProfile(id = "gigi-1", handle = "gigi"),
                        localProfile(id = "gigi-2", handle = "gigilla"),
                    )
            }
            val querier = RecordingQuerier()

            val result = repository(profilesDao = profilesDao, querier = querier).searchUsers(query = "@Gi", limit = 2)

            assertEquals(listOf("gigi-1", "gigi-2"), result.map { it.metadata.profileId })
            assertTrue(
                querier.metadataFilters.isEmpty(),
                "a full page from the local cache needs nothing from a relay",
            )
        }

    @Test
    fun `relays are asked only for what the local cache did not already cover`() =
        runTest {
            val profilesDao = mockk<ProfileDataDao> {
                coEvery { findProfilesByPrefix(prefix = "gi", limit = 10) } returns
                    listOf(localProfile(id = "gigi-1", handle = "gigi"))
            }
            val querier = RecordingQuerier()

            val result = repository(profilesDao = profilesDao, querier = querier).searchUsers(query = "@Gi", limit = 10)

            assertEquals(listOf("gigi-1"), result.map { it.metadata.profileId })
            assertTrue(
                querier.metadataFilters.isNotEmpty(),
                "the relay is still asked to fill the remaining 9 slots",
            )
        }

    // ------------------------------------------------------------------------------- harness

    private fun localProfile(id: String, handle: String) =
        LocalProfileData(
            ownerId = id,
            eventId = "$id-metadata",
            createdAt = 1_700_000_000L,
            raw = "",
            handle = handle,
        )

    private class RecordingQuerier : RelayEventQuerier {
        val metadataFilters = mutableListOf<RelayFilter>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            metadataFilters += filter
            return emptyList()
        }
    }

    private fun repository(profilesDao: ProfileDataDao, querier: RelayEventQuerier): ExploreRepositoryImpl {
        val dispatcher = UnconfinedTestDispatcher()
        val database = mockk<CachingDatabase> {
            every { profiles() } returns profilesDao
        }
        return ExploreRepositoryImpl(
            dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
            },
            database = database,
            relayEventQuerier = querier,
        )
    }
}
