package net.primal.android.networking.relays

import net.primal.android.user.domain.toRelay

val FALLBACK_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.nostr.band",
    "wss://purplepag.es",
    "wss://relay.snort.social",
    "wss://relay.nostr.net",
)

val FALLBACK_RELAYS = FALLBACK_RELAY_URLS.map { it.toRelay() }
