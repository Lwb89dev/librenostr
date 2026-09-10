package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import io.github.aakira.napier.Napier
import java.util.concurrent.ConcurrentHashMap
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
import net.primal.android.networking.relays.FALLBACK_RELAY_URLS
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.cleanWebSocketUrl
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
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

    /**
     * Resolved inboxes, kept for the process lifetime.
     *
     * Resolving one costs up to two relay round trips, and it used to happen twice per send plus
     * once per inbox poll. A relay list is a slow-moving replaceable event, so re-reading it on
     * every message bought nothing and made each send wait out two query timeouts.
     */
    private val deliveryRelayCache = ConcurrentHashMap<String, List<Relay>>()

    /** Declared kind-10050 inboxes only, kept separately because strict sends must not see the
     * fallback chain's guesses as if the recipient had announced them. */
    private val inboxCache = ConcurrentHashMap<String, List<Relay>>()

    /** Accounts whose kind-10050 announcement this process has already handled. */
    private val announcedInboxes = ConcurrentHashMap.newKeySet<String>()

    override suspend fun sendMessage(
        userId: String,
        receiverId: String,
        content: String,
        extraTags: List<JsonArray>,
    ): Nip17Message {
        val rumorTemplate = EventTemplate<ChatMessageEvent>(
            createdAt = Clock.System.now().epochSeconds,
            kind = NostrEventKind.PrivateDirectMessage.value,
            tags = (listOf(receiverId.asPTag(relayHint = null)) + extraTags)
                .map { it.toQuartzTag() }
                .toTypedArray(),
            content = content,
        )
        return deliver(
            userId = userId,
            receiverId = receiverId,
            // Strict NIP-17 for chat: a kind-10050 is the recipient's own statement that they
            // read gift wraps. Without one there is no evidence their client would ever see this,
            // and the caller has a legacy kind-4 path that any client can read — see
            // ChatRepositoryImpl.sendMessage. A private reply has no such fallback and is sent
            // permissively instead.
            requireAnnouncedInbox = true,
            create = { signer ->
                NIP17Factory().createMessageNIP17(rumorTemplate, signer).asDelivery()
            },
        )
    }

    override suspend fun sendPrivateReply(
        userId: String,
        receiverId: String,
        content: String,
        threadTags: List<JsonArray>,
    ): Nip17Message {
        val rumorTemplate = EventTemplate<TextNoteEvent>(
            createdAt = Clock.System.now().epochSeconds,
            kind = NostrEventKind.ShortTextNote.value,
            tags = (listOf(receiverId.asPTag(relayHint = null)) + threadTags)
                .map { it.toQuartzTag() }
                .toTypedArray(),
            content = content,
        )
        return deliver(
            userId = userId,
            receiverId = receiverId,
            requireAnnouncedInbox = false,
            create = { signer ->
                val rumor = signer.sign<TextNoteEvent>(rumorTemplate)
                val wraps = listOf(receiverId, userId).distinct().map { recipientId ->
                    GiftWrapEvent.create(
                        event = SealedRumorEvent.create(
                            event = rumor,
                            encryptTo = recipientId,
                            signer = signer,
                        ),
                        recipientPubKey = recipientId,
                    )
                }
                Nip17Delivery(rumor, wraps)
            },
        )
    }

    private suspend fun deliver(
        userId: String,
        receiverId: String,
        requireAnnouncedInbox: Boolean,
        create: suspend (LibreNostrQuartzSigner) -> Nip17Delivery,
    ): Nip17Message {
        val receiverRelays = when {
            requireAnnouncedInbox -> findAnnouncedInbox(receiverId)
                .ifEmpty { throw Nip17RelayListNotFoundException(receiverId) }
            else -> findDeliveryRelays(receiverId)
        }
        val senderRelays = findDeliveryRelays(userId)
        Napier.i {
            "NIP-17 delivery: receiver=${receiverRelays.joinToString { it.url }} " +
                "sender=${senderRelays.joinToString { it.url }}"
        }
        val result = create(LibreNostrQuartzSigner(pubKey = userId, notary = notary))
        val wrapsByRecipient = result.wraps.associateBy { it.recipientPubKey() }
        val receiverWrap = requireNotNull(wrapsByRecipient[receiverId]) {
            "Quartz did not create the recipient Gift Wrap."
        }

        // Recipient delivery defines success. A sender-copy failure is recoverable from the local
        // optimistic copy and must not make a successfully delivered message look unsent.
        relays.publishEvent(receiverWrap.asDomain(), receiverRelays)
        // A message to yourself produces exactly one wrap, and it has already been published.
        wrapsByRecipient[userId]?.takeIf { it.id != receiverWrap.id }?.let { senderWrap ->
            runCatching { relays.publishEvent(senderWrap.asDomain(), senderRelays) }
                .onFailure { error -> Napier.w(error) { "NIP-17 sender-copy publication failed." } }
        }
        // Announcing the inbox is what lets the other side reply, but it is not what this send
        // waits on: doing it after delivery keeps an unreachable relay from failing the message.
        announceOwnDmRelaysIfMissing(userId = userId, resolved = senderRelays)

        return result.msg.asNip17Message(outerEventId = receiverWrap.id)
    }

    override suspend fun resolveInboxRelays(userId: String): List<String> =
        findDeliveryRelays(userId).map { it.url }

    override suspend fun fetchMessages(userId: String, limit: Int): List<Nip17Message> {
        val dmRelays = findDeliveryRelays(userId)
        Napier.i { "NIP-17 inbox poll on ${dmRelays.joinToString { it.url }}" }
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
            val dmRelays = findDeliveryRelays(userId)
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
            // The seal's tags are not asserted empty: NIP-40 lets a sender attach an `expiration`
            // tag to the seal, and Quartz does exactly that for a self-destructing message.
            // Rejecting the whole envelope over it silently dropped valid messages.
            require(sealDomain.hasValidIdAndSignature()) { "Seal ${seal.id} has a bad id or signature." }

            val rumor = seal.unsealThrowing(signer)
            require(rumor.kind in SUPPORTED_RUMOR_KINDS) { "Unsupported NIP-17 rumor kind ${rumor.kind}." }
            // NIP-59 requires the seal's author to be the rumor's author. Anything else is someone
            // trying to put words in another person's mouth inside an envelope only we can open.
            require(seal.pubKey == rumor.pubKey) { "Seal author ${seal.pubKey} does not match the rumor's." }
            require(
                rumor.tags.any { tag -> tag.getOrNull(0) == "p" && tag.getOrNull(1) == userId } ||
                    rumor.pubKey == userId,
            ) { "Rumor ${rumor.id} is addressed to nobody this account knows about." }
            require(rumor.id == rumor.asDomain().toUnsigned().calculateEventIdHex()) {
                "Rumor id ${rumor.id} does not match its own content."
            }
            rumor.asNip17Message(outerEventId = outer.id)
        }.onFailure { error ->
            Napier.w(error) { "Rejected invalid or undecryptable NIP-17 Gift Wrap ${outer.id}." }
        }.getOrNull()

    /**
     * Publishes this account's kind-10050 inbox announcement, once, when it does not have one.
     *
     * Deliberately best effort and off the send path. It used to run before every delivery and
     * throw when it could not complete, which turned "this account has not announced an inbox
     * yet" into "this message cannot be sent" — the single most common way a send failed, since
     * a fresh account has no kind-10050 and the lookup that decides that also fails on a slow
     * relay. Nothing here can stop a message that has already been delivered.
     */
    private suspend fun announceOwnDmRelaysIfMissing(userId: String, resolved: List<Relay>) {
        if (announcedInboxes.contains(userId) || resolved.isEmpty()) return
        if (findAnnouncedInbox(userId).isNotEmpty()) {
            announcedInboxes.add(userId)
            return
        }

        val relayList = NostrUnsignedEvent(
            pubKey = userId,
            kind = NostrEventKind.PrivateDirectMessageRelayList.value,
            tags = resolved.map { relay ->
                buildJsonArray {
                    add(JsonPrimitive("relay"))
                    add(JsonPrimitive(relay.url))
                }
            },
            content = "",
        )
        runCatching {
            val signed = notary.signNostrEvent(relayList).unwrapOrThrow()
            // Published to the very relays that were just resolved as this account's inbox. The
            // generic user-relay pool may hold them as read-only, which makes the announcement
            // fail there and leaves every future sender unable to find this inbox.
            relays.publishEvent(signed, resolved)
            announcedInboxes.add(userId)
            Napier.i { "Announced NIP-17 inbox on ${resolved.joinToString { it.url }}" }
        }.onFailure { error -> Napier.w(error) { "NIP-17 inbox announcement failed." } }
    }

    /** The recipient's declared NIP-17 inbox (kind 10050), cached for the process lifetime. */
    private suspend fun findAnnouncedInbox(userId: String): List<Relay> {
        inboxCache[userId]?.let { return it }
        val resolved = queryAnnouncedInbox(userId)
        if (resolved.isNotEmpty()) inboxCache[userId] = resolved
        return resolved
    }

    private suspend fun queryAnnouncedInbox(userId: String): List<Relay> {
        val event = relays.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.PrivateDirectMessageRelayList.value),
                authors = listOf(userId),
                limit = 5,
            ),
        ).filter { it.hasValidIdAndSignature() }.maxByOrNull { it.createdAt } ?: return emptyList()
        return event.tags.mapNotNull { tag ->
            tag.stringAt(1)?.takeIf { tag.stringAt(0) == "relay" }?.let { url ->
                Relay(url = url.trim().cleanWebSocketUrl(), read = true, write = true)
            }
        }.distinctBy { it.url }.take(MAX_DM_RELAYS)
    }

    /**
     * Where a gift wrap for [userId] has to go, resolved once per session and then reused.
     *
     * This is Amethyst's own policy (`DmActions.resolveDmRelays`, permissive mode) applied in the
     * same order: the recipient's kind-10050 inbox, then their NIP-65 read relays while that inbox
     * has not propagated or was never published, then a bootstrap pool as the last resort. Strict
     * NIP-17 would refuse to send at that point, and this app used to; the result was that DMs and
     * private replies to anyone without a kind-10050 — most of the network, still — simply could
     * not be sent. A gift wrap carries nothing but an ephemeral key and the recipient's `p` tag,
     * so a public relay learns no more from holding one than NIP-59 already accepts.
     *
     * A gift wrap is always published with write enabled: the read/write marker states the
     * recipient's subscription preference, not the sender's permission to deliver there.
     */
    private suspend fun findDeliveryRelays(userId: String): List<Relay> {
        deliveryRelayCache[userId]?.let { return it }

        val resolved = findAnnouncedInbox(userId)
            .ifEmpty { findNip65ReadRelays(userId) }
            .ifEmpty { relays.configuredUserRelays(userId).filter { it.read } }
            .ifEmpty { FALLBACK_RELAY_URLS.map { Relay(url = it, read = true, write = true) } }
            .distinctBy { it.url }
            .take(MAX_DM_RELAYS)
            .map { it.copy(read = true, write = true) }

        if (resolved.isNotEmpty()) deliveryRelayCache[userId] = resolved
        return resolved
    }

    private suspend fun findNip65ReadRelays(userId: String): List<Relay> {
        val relayList = relays.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.RelayListMetadata.value),
                authors = listOf(userId),
                limit = 5,
            ),
        ).filter { it.hasValidIdAndSignature() }.maxByOrNull { it.createdAt } ?: return emptyList()

        return relayList.tags.mapNotNull { tag ->
            val marker = tag.stringAt(2)
            tag.stringAt(1)?.takeIf { tag.stringAt(0) == "r" && marker != "write" }?.let { url ->
                Relay(url = url.trim().cleanWebSocketUrl(), read = true, write = true)
            }
        }.distinctBy { it.url }
    }

    private companion object {
        const val MAX_DM_RELAYS = 3

        /** Rumor kinds this transport understands: NIP-17 chat, NIP-17 file, gift-wrapped note. */
        val SUPPORTED_RUMOR_KINDS = setOf(
            NostrEventKind.PrivateDirectMessage.value,
            NostrEventKind.PrivateDirectMessageFile.value,
            NostrEventKind.ShortTextNote.value,
        )
    }
}

private data class Nip17Delivery(
    val msg: Event,
    val wraps: List<GiftWrapEvent>,
)

private fun NIP17Factory.Result.asDelivery() = Nip17Delivery(msg = msg, wraps = wraps)

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
    kind = kind,
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
