package net.primal.android.networking.relays

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import net.primal.domain.nostr.relay.RelayFilter

fun RelayFilter.toJsonObject(): JsonObject =
    buildRelayFilter(
        kinds = kinds,
        authors = authors,
        ids = ids,
        limit = limit,
        since = since,
        until = until,
        eventTags = eventTags,
        pubkeyTags = pubkeyTags,
    )

fun buildRelayFilter(
    kinds: List<Int>? = null,
    authors: List<String>? = null,
    ids: List<String>? = null,
    limit: Int? = null,
    since: Long? = null,
    until: Long? = null,
    eventTags: List<String>? = null,
    pubkeyTags: List<String>? = null,
): JsonObject =
    buildJsonObject {
        if (!kinds.isNullOrEmpty()) {
            put("kinds", buildJsonArray { kinds.forEach { add(it) } })
        }
        if (!authors.isNullOrEmpty()) {
            put("authors", buildJsonArray { authors.forEach { add(it) } })
        }
        if (!ids.isNullOrEmpty()) {
            put("ids", buildJsonArray { ids.forEach { add(it) } })
        }
        if (limit != null) put("limit", JsonPrimitive(limit))
        if (since != null) put("since", JsonPrimitive(since))
        if (until != null) put("until", JsonPrimitive(until))
        if (!eventTags.isNullOrEmpty()) {
            put("#e", buildJsonArray { eventTags.forEach { add(it) } })
        }
        if (!pubkeyTags.isNullOrEmpty()) {
            put("#p", buildJsonArray { pubkeyTags.forEach { add(it) } })
        }
    }
