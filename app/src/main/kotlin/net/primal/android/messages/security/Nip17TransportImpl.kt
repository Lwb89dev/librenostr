package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import io.github.aakira.napier.Napier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.android.core.serialization.json.NostrNotaryJson
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.user.domain.Relay
import net.primal.core.utils.runCatching
import net.primal.core.utils.onFailure
import net.primal.domain.messages.Nip17Message
import net.primal.domain.messages.Nip17RelayListNotFoundException
import net.primal.domain.messages.Nip17Transport
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.cryptography.hasValidIdAndSignature
import net.primal.domain.nostr.cryptography.utils.toHex
import net.primal.domain.nostr.cryptography.utils.unwrapOrThrow
import net.primal.domain.nostr.relay.RelayFilter

@Singleton
class Nip17TransportImpl @Inject constructor(
    private val relays: RelaysSocketManager,
    private val notary: NostrNotary,
) : Nip17Transport {

    override suspend fun sendMessage(
        userId: String,
        receiverId: String,
        content: String,
        extraTags: List<JsonArray>,
    ): Nip17Message {
        require(userId != receiverId) { "NIP-17 self messages are not supported here." }
        val receiverRelays = findDmRelays(receiverId)
            .ifEmpty { throw Nip17RelayListNotFoundException(receiverId) }
        val senderRelays = ensureOwnDmRelays(userId)
        val signer = LibreNostrQuartzSigner(pubKey = userId, notary = notary)
        val rumorTemplate = EventTemplate<ChatMessageEvent>(
            createdAt = Clock.System.now().epochSeconds,
            kind = NostrEventKind.PrivateDirectMessage.value,
            tags = (listOf(receiverId.asPTag(receiverRelays.firstOrNull()?.url)) + extraTags)
                .map { it.toQuartzTag() }
                .toTypedArray(),
            content = content,
        )
        val result = NIP17Factory().createMessageNIP17(rumorTemplate, signer)
        val wrapsByRecipient = result.wraps.associateBy { it.recipientPubKey() }
        val receiverWrap = requireNotNull(wrapsByRecipient[receiverId]) {
            "Quartz did not create the recipient Gift Wrap."
        }

        // Recipient delivery defines success. A sender-copy failure is recoverable from the local
        // optimistic copy and must not make a successfully delivered message look unsent.
        relays.publishEvent(receiverWrap.asDomain(), receiverRelays)
        wrapsByRecipient[userId]?.let { senderWrap ->
            runCatching { relays.publishEvent(senderWrap.asDomain(), senderRelays) }
                .onFailure { error -> Napier.w(error) { "NIP-17 sender-copy publication failed." } }
        }

        return result.msg.asNip17Message(outerEventId = receiverWrap.id)
    }

    override suspend fun fetchMessages(userId: String, limit: Int): List<Nip17Message> {
        val dmRelays = ensureOwnDmRelays(userId)
        return relays.queryEvents(
            filter = RelayFilter(
                kinds = listOf(NostrEventKind.GiftWrap.value),
                pubkeyTags = listOf(userId),
                limit = limit,
            ),
            relays = dmRelays,
        ).events.distinctBy { it.id }.mapNotNull { unwrap(userId = userId, outer = it) }
    }

    override fun subscribeMessages(userId: String): Flow<Nip17Message> =
        flow {
            val dmRelays = ensureOwnDmRelays(userId)
            emitAll(
                relays.subscribeEvents(
                    filter = RelayFilter(
                        kinds = listOf(NostrEventKind.GiftWrap.value),
                        pubkeyTags = listOf(userId),
                        since = Clock.System.now().epochSeconds,
                        limit = Nip17Transport.DEFAULT_FETCH_LIMIT,
                    ),
                    relays = dmRelays,
                ),
            )
        }.mapNotNull { unwrap(userId = userId, outer = it) }

    private suspend fun unwrap(userId: String, outer: NostrEvent): Nip17Message? =
        runCatching {
            require(outer.kind == NostrEventKind.GiftWrap.value)
            require(outer.hasValidIdAndSignature())
            require(outer.tags.any { it.stringAt(0) == "p" && it.stringAt(1) == userId })

            val signer = LibreNostrQuartzSigner(pubKey = userId, notary = notary)
            val giftWrap = com.vitorpamplona.quartz.nip01Core.core.Event
                .fromJson(NostrNotaryJson.encodeToString(outer)) as GiftWrapEvent
            val seal = giftWrap.unwrapThrowing(signer) as SealedRumorEvent
            val sealDomain = seal.asDomain()
            require(seal.tags.isEmpty())
            require(sealDomain.hasValidIdAndSignature())

            val rumor = seal.unsealThrowing(signer)
            require(rumor.kind == NostrEventKind.PrivateDirectMessage.value)
            require(seal.pubKey == rumor.pubKey)
            require(
                rumor.tags.any { tag -> tag.getOrNull(0) == "p" && tag.getOrNull(1) == userId } ||
                    rumor.pubKey == userId,
            )
            require(rumor.id == rumor.asDomain().toUnsigned().calculateEventIdHex())
            rumor.asNip17Message(outerEventId = outer.id)
        }.onFailure { error ->
            Napier.w(error) { "Rejected invalid or undecryptable NIP-17 Gift Wrap ${outer.id}." }
        }.getOrNull()

    private suspend fun ensureOwnDmRelays(userId: String): List<Relay> {
        findDmRelays(userId).takeIf { it.isNotEmpty() }?.let { return it }
        val selected = relays.configuredUserRelays(userId)
            .filter { it.read }
            .distinctBy { it.url }
            .take(MAX_DM_RELAYS)
            .map { it.copy(read = true, write = true) }
        if (selected.isEmpty()) throw Nip17RelayListNotFoundException(userId)

        val relayList = NostrUnsignedEvent(
            pubKey = userId,
            kind = NostrEventKind.PrivateDirectMessageRelayList.value,
            tags = selected.map { relay ->
                buildJsonArray {
                    add(JsonPrimitive("relay"))
                    add(JsonPrimitive(relay.url))
                }
            },
            content = "",
        )
        val signed = notary.signNostrEvent(relayList).unwrapOrThrow()
        relays.publishEvent(signed)
        return selected
    }

    private suspend fun findDmRelays(userId: String): List<Relay> {
        val event = relays.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.PrivateDirectMessageRelayList.value),
                authors = listOf(userId),
                limit = 5,
            ),
        ).filter { it.hasValidIdAndSignature() }.maxByOrNull { it.createdAt } ?: return emptyList()
        return event.tags.mapNotNull { tag ->
            tag.stringAt(1)?.takeIf { tag.stringAt(0) == "relay" }?.let {
                Relay(url = it, read = true, write = true)
            }
        }.distinctBy { it.url }.take(MAX_DM_RELAYS)
    }

    private companion object {
        const val MAX_DM_RELAYS = 3
    }
}

private fun String.asPTag(relayHint: String?): JsonArray = buildJsonArray {
    add(JsonPrimitive("p"))
    add(JsonPrimitive(this@asPTag))
    relayHint?.let { add(JsonPrimitive(it)) }
}

private fun JsonArray.toQuartzTag(): Array<String> = map { (it as JsonPrimitive).content }.toTypedArray()

private fun JsonArray.stringAt(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.content

private fun com.vitorpamplona.quartz.nip01Core.core.Event.asDomain() = NostrEvent(
    id = id,
    pubKey = pubKey,
    createdAt = createdAt,
    kind = kind,
    tags = tags.map { tag -> buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } } },
    content = content,
    sig = sig,
)

private fun com.vitorpamplona.quartz.nip01Core.core.Event.asNip17Message(outerEventId: String) = Nip17Message(
    eventId = id,
    outerEventId = outerEventId,
    senderId = pubKey,
    recipientIds = tags.filter { it.getOrNull(0) == "p" }.mapNotNull { it.getOrNull(1) },
    createdAt = createdAt,
    content = content,
    tags = tags.map { tag -> buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } } },
)

private fun NostrEvent.toUnsigned() = NostrUnsignedEvent(
    pubKey = pubKey,
    createdAt = createdAt,
    kind = kind,
    tags = tags,
    content = content,
)

private fun NostrUnsignedEvent.calculateEventIdHex(): String = calculateEventId().toHex()
