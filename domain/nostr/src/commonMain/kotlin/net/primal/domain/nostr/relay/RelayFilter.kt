package net.primal.domain.nostr.relay

data class RelayFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val limit: Int? = null,
    val since: Long? = null,
    val until: Long? = null,
    val eventTags: List<String>? = null,
    val pubkeyTags: List<String>? = null,
)
