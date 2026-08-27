package net.primal.android.networking.relays

import net.primal.domain.nostr.NostrEvent

data class RelayPoolQueryResult(
    val events: List<NostrEvent> = emptyList(),
    val eoseRelays: Set<String> = emptySet(),
    val failedRelays: Map<String, String> = emptyMap(),
    val duplicateCount: Int = 0,
) {
    val uniqueEventCount: Int get() = events.size
}

data class RelayQueryStats(
    val requestedRelays: Int,
    val eoseRelays: Int,
    val failedRelays: Int,
    val uniqueEvents: Int,
    val duplicates: Int,
    val timedOut: Boolean,
)
