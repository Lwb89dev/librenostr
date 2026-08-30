package net.primal.android.user.repository

import androidx.room.withTransaction
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.android.networking.relays.FALLBACK_RELAYS
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.nostr.publish.NostrPublisher
import net.primal.android.user.accounts.parseNip65Relays
import net.primal.android.user.db.UsersDatabase
import net.primal.android.user.domain.Relay as RelayDO
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.domain.UserRelays
import net.primal.android.user.domain.cleanWebSocketUrl
import net.primal.android.user.domain.mapToRelayDO
import net.primal.android.user.domain.mapToRelayPO
import net.primal.android.user.domain.toRelay
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

@Suppress("TooManyFunctions")
class RelayRepository @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val usersDatabase: UsersDatabase,
    private val nostrPublisher: NostrPublisher,
    private val relayEventQuerier: RelayEventQuerier,
) {
    fun observeUserRelays(userId: String) =
        usersDatabase.relays().observeRelays(userId)
            .map { relays -> relays.filter { it.kind == RelayKind.UserRelay } }

    fun findRelays(userId: String, kind: RelayKind) = usersDatabase.relays().findRelays(userId, kind)

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun bootstrapUserRelays(userId: String, relayUrls: List<String>) =
        withContext(dispatchers.io()) {
            val relays = relayUrls.map { it.toRelay() }
            replaceUserRelays(userId, relays)
            nostrPublisher.publishRelayList(userId, relays)
        }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun bootstrapDefaultUserRelays(userId: String) =
        withContext(dispatchers.io()) {
            replaceUserRelays(userId, FALLBACK_RELAYS)
            nostrPublisher.publishRelayList(userId, FALLBACK_RELAYS)
        }

    private suspend fun fetchUserRelaysFromRelays(userId: String): List<RelayDO>? {
        val events = runCatching {
            relayEventQuerier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.RelayListMetadata.value),
                    authors = listOf(userId),
                    limit = 5,
                ),
            )
        }.getOrNull().orEmpty()
        val latest = events.maxByOrNull { it.createdAt }
        if (latest != null) {
            Napier.i {
                "Relay kind-10002 for $userId: events=${events.size} createdAt=${latest.createdAt}"
            }
        }
        return latest?.tags?.parseNip65Relays()
    }

    suspend fun ensureLocalBootstrapRelays(userId: String) = withContext(dispatchers.io()) {
        if (findRelays(userId, RelayKind.UserRelay).isEmpty()) {
            replaceUserRelays(userId, FALLBACK_RELAYS)
        }
    }

    suspend fun syncUserRelaysOrBootstrap(userId: String) {
        ensureLocalBootstrapRelays(userId)
        val fromRelays = fetchUserRelaysFromRelays(userId)?.takeIf { it.isNotEmpty() }
        if (fromRelays != null) replaceUserRelays(userId, fromRelays)
    }

    private suspend fun currentUserRelays(userId: String): List<RelayDO> {
        return findRelays(userId, RelayKind.UserRelay).map { it.mapToRelayDO() }
    }

    suspend fun fetchAndUpdateUserRelays(userId: String) {
        val relayList = fetchUserRelaysFromRelays(userId)?.takeIf { it.isNotEmpty() }
        if (relayList != null) replaceUserRelays(userId, relayList)
    }

    private suspend fun fetchUserRelaysFromRelays(userIds: List<String>): List<UserRelays> {
        if (userIds.isEmpty()) return emptyList()
        val events = runCatching {
            relayEventQuerier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.RelayListMetadata.value),
                    authors = userIds.distinct(),
                    limit = userIds.size * EVENTS_PER_AUTHOR_LIMIT,
                ),
            )
        }.getOrNull().orEmpty()

        return events
            .filter { it.pubKey in userIds }
            .groupBy { it.pubKey }
            .mapNotNull { (pubkey, authorEvents) ->
                authorEvents.maxByOrNull { it.createdAt }
                    ?.tags
                    ?.parseNip65Relays()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { UserRelays(pubkey = pubkey, relays = it) }
            }
    }

    suspend fun fetchAndUpdateUserRelays(userIds: List<String>): List<UserRelays> {
        return fetchUserRelaysFromRelays(userIds).onEach {
            replaceUserRelays(userId = it.pubkey, relays = it.relays)
        }
    }

    private suspend fun replaceUserRelays(userId: String, relays: List<RelayDO>) =
        withContext(dispatchers.io()) {
            usersDatabase.withTransaction {
                usersDatabase.relays().deleteAll(userId = userId, kind = RelayKind.UserRelay)
                usersDatabase.relays().upsertAll(
                    relays = relays
                        .map { it.mapToRelayPO(userId = userId, kind = RelayKind.UserRelay) }
                        .distinctBy { it.url },
                )
            }
        }

    @Throws(NostrPublishException::class)
    suspend fun addRelayAndPublishRelayList(userId: String, url: String) {
        val newRelay = RelayDO(url = url, read = true, write = true)
        updateRelayList(userId = userId) {
            this.toMutableList().apply {
                add(0, newRelay)
            }
        }
    }

    @Throws(NostrPublishException::class)
    suspend fun removeRelayAndPublishRelayList(userId: String, url: String) {
        updateRelayList(userId = userId) {
            this.toMutableList().apply {
                removeIf { it.url == url.cleanWebSocketUrl() }
            }
        }
    }

    @Throws(NostrPublishException::class)
    suspend fun updateRelayPermissionsAndPublishRelayList(
        userId: String,
        url: String,
        read: Boolean,
        write: Boolean,
    ) {
        val cleaned = url.cleanWebSocketUrl()
        if (!read && !write) {
            removeRelayAndPublishRelayList(userId, cleaned)
            return
        }
        updateRelayList(userId = userId) {
            map { relay ->
                if (relay.url.cleanWebSocketUrl() == cleaned) relay.copy(read = read, write = write) else relay
            }
        }
    }

    private suspend fun updateRelayList(userId: String, reducer: List<RelayDO>.() -> List<RelayDO>) =
        withContext(dispatchers.io()) {
            val latestRelayList = currentUserRelays(userId)
            val newRelayList = latestRelayList.reducer()
            nostrPublisher.publishRelayList(userId = userId, relays = newRelayList)
            replaceUserRelays(userId = userId, relays = newRelayList)
        }

    private companion object {
        const val EVENTS_PER_AUTHOR_LIMIT = 3
    }
}
