package net.primal.data.remote.api.messages

import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.primal.core.networking.primal.PrimalApiClient
import net.primal.core.networking.primal.PrimalCacheFilter
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.data.remote.PrimalVerb
import net.primal.data.remote.api.messages.model.ConversationRequestBody
import net.primal.data.remote.api.messages.model.ConversationsResponse
import net.primal.data.remote.api.messages.model.MarkMessagesReadRequestBody
import net.primal.data.remote.api.messages.model.MessagesRequestBody
import net.primal.data.remote.api.messages.model.MessagesResponse
import net.primal.data.remote.mapper.asMessageConversationsSummary
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

internal class MessagesApiImpl(
    private val primalApiClient: PrimalApiClient,
    private val relayEventQuerier: RelayEventQuerier? = null,
) : MessagesApi {

    override suspend fun getConversations(body: ConversationRequestBody): ConversationsResponse {
        relayEventQuerier?.let { querier ->
            val messages = querier.queryDirectMessages(userId = body.userId)
            Napier.d { "Relay DM conversation query returned ${messages.size} events." }
            return ConversationsResponse(
                conversationsSummary = null,
                messages = messages,
                profileMetadata = emptyList(),
                cdnResources = emptyList(),
                primalUserNames = null,
                primalLegendProfiles = null,
                primalPremiumInfo = null,
                blossomServers = emptyList(),
            )
        }
        val response = primalApiClient.query(
            message = PrimalCacheFilter(
                primalVerb = PrimalVerb.GET_DM_CONTACTS.id,
                optionsJson = body.encodeToJsonString(),
            ),
        )

        return ConversationsResponse(
            conversationsSummary = response
                .findPrimalEvent(NostrEventKind.PrimalDirectMessagesConversationsSummary)
                ?.asMessageConversationsSummary(),
            messages = response.filterNostrEvents(NostrEventKind.EncryptedDirectMessages),
            profileMetadata = response.filterNostrEvents(NostrEventKind.Metadata),
            cdnResources = response.filterPrimalEvents(NostrEventKind.PrimalCdnResource),
            primalUserNames = response.findPrimalEvent(NostrEventKind.PrimalUserNames),
            primalLegendProfiles = response.findPrimalEvent(NostrEventKind.PrimalLegendProfiles),
            primalPremiumInfo = response.findPrimalEvent(NostrEventKind.PrimalPremiumInfo),
            blossomServers = response.filterNostrEvents(NostrEventKind.BlossomServerList),
        )
    }

    override suspend fun getMessages(body: MessagesRequestBody): MessagesResponse {
        relayEventQuerier?.let { querier ->
            val messages = querier.queryDirectMessages(
                userId = body.userId,
                participantId = body.participantId,
                limit = body.limit,
                since = body.since,
                until = body.until,
            )
            Napier.d { "Relay DM message query returned ${messages.size} events." }
            return MessagesResponse(
                messages = messages,
            )
        }
        val response = primalApiClient.query(
            message = PrimalCacheFilter(
                primalVerb = PrimalVerb.GET_DMS.id,
                optionsJson = body.encodeToJsonString(),
            ),
        )

        return MessagesResponse(
            paging = response.findPrimalEvent(NostrEventKind.PrimalPaging)?.content?.decodeFromJsonStringOrNull(),
            messages = response.filterNostrEvents(NostrEventKind.EncryptedDirectMessages),
            profileMetadata = response.filterNostrEvents(NostrEventKind.Metadata),
            cdnResources = response.filterPrimalEvents(NostrEventKind.PrimalCdnResource),
            primalUserNames = response.findPrimalEvent(NostrEventKind.PrimalUserNames),
            primalLegendProfiles = response.findPrimalEvent(NostrEventKind.PrimalLegendProfiles),
            primalPremiumInfo = response.findPrimalEvent(NostrEventKind.PrimalPremiumInfo),
        )
    }

    override suspend fun markConversationAsRead(body: MarkMessagesReadRequestBody) {
        if (relayEventQuerier != null) return
        primalApiClient.query(
            message = PrimalCacheFilter(
                primalVerb = PrimalVerb.MARK_DM_CONVERSATION_AS_READ.id,
                optionsJson = body.encodeToJsonString(),
            ),
        )
    }

    override suspend fun markAllMessagesAsRead(authorization: NostrEvent) {
        if (relayEventQuerier != null) return
        primalApiClient.query(
            message = PrimalCacheFilter(
                primalVerb = PrimalVerb.MARK_ALL_DMS_AS_READ.id,
                optionsJson = MarkMessagesReadRequestBody(authorization = authorization).encodeToJsonString(),
            ),
        )
    }
}

/**
 * NIP-04 messages are ordinary relay events (kind 4). There is no relay-side
 * equivalent of Primal's contact summary, so we query both directions and let
 * the repository build the local conversation index from the events.
 */
private suspend fun RelayEventQuerier.queryDirectMessages(
    userId: String,
    participantId: String? = null,
    limit: Int? = null,
    since: Long? = null,
    until: Long? = null,
): List<NostrEvent> = coroutineScope {
    val filters = if (participantId == null) {
        listOf(
            RelayFilter(
                kinds = listOf(NostrEventKind.EncryptedDirectMessages.value),
                authors = listOf(userId),
                limit = limit,
                since = since,
                until = until,
            ),
            RelayFilter(
                kinds = listOf(NostrEventKind.EncryptedDirectMessages.value),
                pubkeyTags = listOf(userId),
                limit = limit,
                since = since,
                until = until,
            ),
        )
    } else {
        listOf(
            RelayFilter(
                kinds = listOf(NostrEventKind.EncryptedDirectMessages.value),
                authors = listOf(userId),
                pubkeyTags = listOf(participantId),
                limit = limit,
                since = since,
                until = until,
            ),
            RelayFilter(
                kinds = listOf(NostrEventKind.EncryptedDirectMessages.value),
                authors = listOf(participantId),
                pubkeyTags = listOf(userId),
                limit = limit,
                since = since,
                until = until,
            ),
        )
    }

    filters.map { filter -> async { query(filter) } }
        .awaitAll()
        .flatten()
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt }
        .let { events -> if (limit == null) events else events.take(limit) }
}
