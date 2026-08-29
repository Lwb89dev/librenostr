package net.primal.android.user.accounts

import io.github.aakira.napier.Napier
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.android.user.domain.Relay
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.utils.isValidHex

fun String.parseKind3Relays(): List<Relay> {
    val jsonContent = try {
        CommonJson.parseToJsonElement(this)
    } catch (error: SerializationException) {
        Napier.w(throwable = error) { "Failed to parse kind 3 relays" }
        null
    }

    val relays = mutableListOf<Relay>()
    jsonContent?.jsonObject?.entries?.forEach {
        val relayUrl = it.key
        val permissions = it.value.jsonObject
        val read = permissions["read"]?.jsonPrimitive?.boolean ?: false
        val write = permissions["write"]?.jsonPrimitive?.boolean ?: false
        relays.add(Relay(url = relayUrl, read = read, write = write))
    }

    return relays
}

fun List<JsonArray>.parseNip65Relays(): List<Relay> {
    return this.mapNotNull { tag ->
        val marker = (tag.getOrNull(0) as? JsonPrimitive)?.content
        if (marker != "r") return@mapNotNull null
        val url = (tag.getOrNull(1) as? JsonPrimitive)?.content ?: return@mapNotNull null
        val permission = (tag.getOrNull(2) as? JsonPrimitive)?.content?.lowercase()
        Relay(
            url = url,
            read = permission == null || permission == "read",
            write = permission == null || permission == "write",
        )
    }
}

fun List<JsonArray>.parseFollowings(): Set<String> {
    val followings = mutableSetOf<String>()
    this.forEach {
        if (it.getOrNull(0)?.jsonPrimitive?.content == "p") {
            val pubkey = it.getOrNull(1)?.jsonPrimitive?.content
            if (pubkey != null && pubkey.isValidHex()) {
                followings.add(pubkey)
            }
        }
    }
    return followings
}

fun List<JsonArray>.parseInterests(): List<String> {
    val interests = mutableListOf<String>()
    this.forEach {
        if (it.getOrNull(0)?.jsonPrimitive?.content == "t") {
            val hashtag = it.getOrNull(1)?.jsonPrimitive?.content
            if (hashtag != null) interests.add(hashtag)
        }
    }
    return interests
}
