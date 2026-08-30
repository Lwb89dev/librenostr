package net.primal.core.networking.sockets

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.networking.mappers.asNostrEventOrNull
import net.primal.core.networking.mappers.asPrimalEventOrNull
import net.primal.core.networking.serialization.SocketsJson
import net.primal.core.utils.serialization.decodeFromStringOrNull
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.hasValidIdAndSignature
import net.primal.domain.nostr.cryptography.hasValidNip01Id
import net.primal.domain.nostr.cryptography.hasValidSchnorrSignature
import net.primal.domain.nostr.isNotPrimalEventKind
import net.primal.domain.nostr.isNotUnknown
import net.primal.domain.nostr.isPrimalEventKind

private const val MAX_INCOMING_MESSAGE_CHARS = 1024 * 1024

fun String.parseIncomingMessage(): NostrIncomingMessage? {
    if (length > MAX_INCOMING_MESSAGE_CHARS) {
        Napier.w { "Dropping oversized incoming message ($length chars)." }
        return null
    }
    val jsonArray = SocketsJson.decodeFromStringOrNull<JsonArray>(this)
    val verbElement = jsonArray?.elementAtOrNull(0) ?: return null

    return try {
        when (verbElement.toIncomingMessageType()) {
            NostrVerb.Incoming.EVENT -> jsonArray.takeAsEventIncomingMessage()
            NostrVerb.Incoming.EOSE -> jsonArray.takeAsEoseIncomingMessage()
            NostrVerb.Incoming.OK -> jsonArray.takeAsOkIncomingMessage()
            NostrVerb.Incoming.NOTICE -> jsonArray.takeAsNoticeIncomingMessage()
            NostrVerb.Incoming.AUTH -> jsonArray.takeAsAuthIncomingMessage()
            NostrVerb.Incoming.COUNT -> jsonArray.takeAsCountIncomingMessage()
            NostrVerb.Incoming.EVENTS -> jsonArray.takeAsEventsIncomingMessage()
        }
    } catch (error: Exception) {
        Napier.w(error) { "Unable to parse incoming message." }
        null
    }
}

private fun JsonArray.takeAsAuthIncomingMessage(): NostrIncomingMessage? {
    val challenge = elementAtOrNull(1) ?: return null
    return NostrIncomingMessage.AuthMessage(
        challenge = challenge.jsonPrimitive.content,
    )
}

private fun JsonArray.takeAsCountIncomingMessage(): NostrIncomingMessage? {
    val subscriptionId = elementAtOrNull(1)?.toSubscriptionId()
    val count = elementAtOrNull(2)
        ?.jsonObject
        ?.get("count")
        ?.jsonPrimitive?.intOrNull

    return if (subscriptionId != null && count != null) {
        NostrIncomingMessage.CountMessage(
            subscriptionId = subscriptionId,
            count = count,
        )
    } else {
        null
    }
}

private fun JsonArray.takeAsEoseIncomingMessage(): NostrIncomingMessage? {
    val subscriptionElement = elementAtOrNull(1) ?: return null
    return NostrIncomingMessage.EoseMessage(
        subscriptionId = subscriptionElement.toSubscriptionId(),
    )
}

private fun JsonArray.takeAsEventIncomingMessage(): NostrIncomingMessage? {
    val subscriptionId = elementAtOrNull(1)?.toSubscriptionId()
    val event = elementAtOrNull(2)?.jsonObject
    val kind = event?.getMessageNostrEventKind()

    if (subscriptionId == null || kind == null) return null

    val nostrEvent = if (kind.isNotUnknown() && kind.isNotPrimalEventKind()) {
        event.asVerifiedNostrEventOrNull()
    } else {
        null
    }

    val primalEvent = if (kind.isPrimalEventKind()) {
        event.asPrimalEventOrNull()
    } else {
        null
    }

    return NostrIncomingMessage.EventMessage(
        subscriptionId = subscriptionId,
        nostrEvent = nostrEvent,
        primalEvent = primalEvent,
    )
}

private fun JsonArray.takeAsEventsIncomingMessage(): NostrIncomingMessage? {
    val subscriptionId = elementAtOrNull(1)?.toSubscriptionId()
    val events = elementAtOrNull(2)?.jsonArray

    if (subscriptionId == null || events == null) return null

    val nostrEvents = mutableListOf<NostrEvent>()
    val primalEvents = mutableListOf<PrimalEvent>()

    events.map { it.jsonObject }.forEach { jsonEvent ->
        val kind = jsonEvent.getMessageNostrEventKind()
        when {
            kind.isNotUnknown() && kind.isNotPrimalEventKind() -> {
                val nostrEvent = jsonEvent.asVerifiedNostrEventOrNull()
                if (nostrEvent != null) {
                    nostrEvents.add(nostrEvent)
                }
            }

            kind.isPrimalEventKind() -> {
                val primalEvent = jsonEvent.asPrimalEventOrNull()
                if (primalEvent != null) {
                    primalEvents.add(primalEvent)
                } else {
                    // Do not put the full event (which may contain private content) in logcat.
                    Napier.w("Unable to process a legacy event payload.")
                }
            }
        }
    }

    return NostrIncomingMessage.EventsMessage(
        subscriptionId = subscriptionId,
        nostrEvents = nostrEvents,
        primalEvents = primalEvents,
    )
}

private fun JsonObject.asVerifiedNostrEventOrNull(): NostrEvent? {
    val event = asNostrEventOrNull() ?: return null
    if (hasValidNip01Id() && event.hasValidSchnorrSignature()) return event
    return event.takeIf { it.hasValidIdAndSignature() }
}

private fun JsonObject.getMessageNostrEventKind(): NostrEventKind {
    val kind = this["kind"]?.jsonPrimitive?.content?.toIntOrNull()
    return if (kind != null) NostrEventKind.valueOf(kind) else NostrEventKind.Unknown
}

private fun JsonArray.takeAsNoticeIncomingMessage(): NostrIncomingMessage {
    // Some legacy relays send ["NOTICE", subscriptionId, message]; the Nostr spec form is
    // ["NOTICE", message]. Only the 3-element form is addressed to a subscription - without this
    // check the spec form's text lands in subscriptionId and the message itself is lost.
    val hasSubscriptionId = size >= 3
    return NostrIncomingMessage.NoticeMessage(
        subscriptionId = if (hasSubscriptionId) elementAtOrNull(1)?.toSubscriptionId() else null,
        message = elementAtOrNull(if (hasSubscriptionId) 2 else 1)?.jsonPrimitive?.content,
    )
}

private fun JsonArray.takeAsOkIncomingMessage(): NostrIncomingMessage? {
    val eventId = elementAtOrNull(1)?.jsonPrimitive?.content
    val success = elementAtOrNull(2)?.jsonPrimitive?.booleanOrNull
    val message = elementAtOrNull(3)?.jsonPrimitive?.content

    return if (eventId != null && success != null) {
        NostrIncomingMessage.OkMessage(
            eventId = eventId,
            success = success,
            message = message,
        )
    } else {
        null
    }
}

private fun JsonElement.toIncomingMessageType(): NostrVerb.Incoming {
    return when (this.jsonPrimitive.content) {
        "EVENT" -> NostrVerb.Incoming.EVENT
        "EOSE" -> NostrVerb.Incoming.EOSE
        "OK" -> NostrVerb.Incoming.OK
        "AUTH" -> NostrVerb.Incoming.AUTH
        "COUNT" -> NostrVerb.Incoming.COUNT
        "EVENTS" -> NostrVerb.Incoming.EVENTS
        else -> NostrVerb.Incoming.NOTICE
    }
}

private fun JsonElement.toSubscriptionId(): String = this.jsonPrimitive.content
