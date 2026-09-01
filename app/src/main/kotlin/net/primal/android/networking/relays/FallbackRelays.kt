package net.primal.android.networking.relays

import net.primal.android.user.domain.toRelay

/**
 * Relays used when the account has none of its own configured.
 *
 * Chosen by probing rather than by reputation. Each was asked for its NIP-11 document and then
 * opened for a real REQ; the ones kept answered with events and an EOSE on repeated attempts,
 * are free, and do not restrict writes. Measured 2026-09-01 from a single network, so a relay
 * that failed here may still work elsewhere — this is a starting set, not a verdict.
 *
 * Dropped from the previous list:
 *  - `nostr.wine` — NIP-11 reports `payment_required` and `restricted_writes`, and it answered
 *    nothing on three unauthenticated attempts. It stays offered during onboarding, unticked,
 *    because it is a reasonable choice for someone who pays for it.
 *  - `relay.nostr.band` — no NIP-11 document and no events on three attempts. It is primarily a
 *    search and indexing service; its relay endpoint is not dependable for general traffic.
 *  - `purplepag.es` — answered EOSE promptly but returned zero kind 1 events. It serves profile
 *    metadata (kinds 0 and 3), so it belongs in [METADATA_RELAY_URLS], not in a note pool where
 *    it only adds a round trip.
 */
val FALLBACK_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.snort.social",
    "wss://relay.nostr.net",
    "wss://nostr.oxtr.dev",
    "wss://offchain.pub",
    "wss://nostr.mom",
)

/**
 * Relays worth asking for profile metadata specifically.
 *
 * `purplepag.es` exists to serve kind 0 and kind 3 and is unusually complete at it, which is the
 * opposite of useful in a note query and exactly what is wanted when resolving a mention.
 */
val METADATA_RELAY_URLS = listOf(
    "wss://purplepag.es",
)

/**
 * Everything offered during onboarding, with what the probe found about each.
 *
 * Nothing here is ticked by default. The set a user talks to is theirs to choose, and a
 * pre-ticked list reads like an endorsement.
 */
val ONBOARDING_RELAY_OPTIONS = listOf(
    RelayOption(url = "wss://relay.damus.io", note = RelayNote.General),
    RelayOption(url = "wss://nos.lol", note = RelayNote.General),
    RelayOption(url = "wss://relay.snort.social", note = RelayNote.General),
    RelayOption(url = "wss://relay.nostr.net", note = RelayNote.General),
    RelayOption(url = "wss://nostr.oxtr.dev", note = RelayNote.General),
    RelayOption(url = "wss://offchain.pub", note = RelayNote.General),
    RelayOption(url = "wss://nostr.mom", note = RelayNote.General),
    RelayOption(url = "wss://nostr-pub.wellorder.net", note = RelayNote.General),
    RelayOption(url = "wss://nostr.bitcoiner.social", note = RelayNote.General),
    RelayOption(url = "wss://purplepag.es", note = RelayNote.ProfilesOnly),
    RelayOption(url = "wss://nostr.wine", note = RelayNote.Paid),
)

data class RelayOption(
    val url: String,
    val note: RelayNote,
)

enum class RelayNote {
    General,
    ProfilesOnly,
    Paid,
}

val FALLBACK_RELAYS = FALLBACK_RELAY_URLS.map { it.toRelay() }

internal fun String.isValidRelayUrl(): Boolean {
    val url = trim().lowercase()
    if (!url.startsWith("wss://")) return false
    val host = url.removePrefix("wss://").substringBefore("/").substringBefore(":")
    return host.contains('.') &&
        !host.startsWith('.') &&
        !host.endsWith('.') &&
        host.none { it.isWhitespace() }
}
