package net.primal.android.user.repository

import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.ensureHttpOrHttps
import net.primal.core.utils.map
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asServerTag
import net.primal.domain.publisher.PrimalPublisher

class BlossomRepository @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val userAccountsStore: UserAccountsStore,
    private val primalPublisher: PrimalPublisher,
) {

    private companion object {
        private val DEFAULT_BLOSSOM_LIST = listOf(
            "https://blossom.band",
            "https://cdn.satellite.earth",
        )
    }

    suspend fun ensureBlossomServerList(userId: String): List<String> {
        val userAccount = userAccountsStore.findByIdOrNull(userId)
        val existingList = userAccount?.blossomServers.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.contains("blossom.primal.net", ignoreCase = true) }
            .map { it.ensureHttpOrHttps().trimEnd('/') }
            .distinct()
        // Keep user-published servers first, but always retain public fallbacks. A stale
        // Blossom list must never make uploads fail permanently after a server disappears.
        return (existingList + DEFAULT_BLOSSOM_LIST).distinct()
    }

    suspend fun publishBlossomServerList(userId: String, servers: List<String>) {
        withContext(dispatcherProvider.io()) {
            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.BlossomServerList.value,
                    tags = servers.map { it.ensureHttpOrHttps().asServerTag() },
                    content = "",
                ),
            )
            persistBlossomServersLocally(userId = userId, blossomServers = servers.map { it.ensureHttpOrHttps() })
        }
    }

    private suspend fun persistBlossomServersLocally(userId: String, blossomServers: List<String>) {
        userAccountsStore.getAndUpdateAccount(userId) {
            copy(blossomServers = blossomServers)
        }
    }
}
