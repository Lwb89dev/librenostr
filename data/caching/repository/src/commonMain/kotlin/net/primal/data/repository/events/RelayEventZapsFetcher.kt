package net.primal.data.repository.events

import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.mappers.remote.asProfileDataPOFromRelay
import net.primal.data.repository.mappers.remote.extractZapRequestOrNull
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapAsEventZapDO
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.shared.data.local.db.withTransaction

/** Loads zap receipts from relays and indexes them for the existing paging UI. */
internal class RelayEventZapsFetcher(
    private val querier: RelayEventQuerier,
    private val database: CachingDatabase,
) {

    suspend fun fetch(
        eventTags: List<String> = emptyList(),
        addressTags: List<String> = emptyList(),
        limit: Int,
    ): Int {
        if (eventTags.isEmpty() && addressTags.isEmpty()) return 0

        val receipts = querier.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.Zap.value),
                eventTags = eventTags.distinct(),
                addressTags = addressTags.distinct(),
                limit = limit,
            ),
        ).distinctBy { it.id }

        if (receipts.isEmpty()) return 0

        val senders = receipts.mapNotNull { it.extractZapRequestOrNull()?.pubKey }.distinct()
        val profiles = if (senders.isEmpty()) {
            emptyList()
        } else {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = senders,
                    limit = senders.size,
                ),
            ).latestMetadataByPubkey().map { it.asProfileDataPOFromRelay() }
        }
        val zaps = receipts.mapAsEventZapDO(profilesMap = profiles.associateBy { it.ownerId })

        database.withTransaction {
            database.profiles().insertOrUpdateAll(data = profiles)
            database.eventZaps().upsertAll(data = zaps)
        }
        return zaps.size
    }
}
