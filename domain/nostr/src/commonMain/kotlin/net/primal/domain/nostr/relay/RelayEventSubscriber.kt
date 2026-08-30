package net.primal.domain.nostr.relay

import kotlinx.coroutines.flow.Flow
import net.primal.domain.nostr.NostrEvent

/** Relay-backed long-lived Nostr subscription capability. */
interface RelayEventSubscriber : RelayEventQuerier {
    fun subscribe(filter: RelayFilter): Flow<NostrEvent>
}
