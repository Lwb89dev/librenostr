package net.primal.domain.nostr.relay

import net.primal.domain.nostr.NostrEvent

interface RelayEventQuerier {
    suspend fun query(filter: RelayFilter): List<NostrEvent>
}
