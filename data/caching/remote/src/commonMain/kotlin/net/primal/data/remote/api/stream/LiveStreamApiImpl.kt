package net.primal.data.remote.api.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonPrimitive
import net.primal.data.remote.api.stream.model.FindLiveStreamRequestBody
import net.primal.data.remote.api.stream.model.FindLiveStreamResponse
import net.primal.data.remote.api.stream.model.LiveFeedResponse
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.asATagValue
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventSubscriber
import net.primal.domain.nostr.relay.RelayFilter

class LiveStreamApiImpl(
    private val relayEventSubscriber: RelayEventSubscriber,
) : LiveStreamApi {

    override suspend fun subscribeToLiveEvent(
        streamingNaddr: Naddr,
        userId: String,
        contentModerationMode: String,
    ): Flow<LiveFeedResponse> {
        // Live chat and zap receipts reference the live activity with an `a`
        // tag. Moderation is applied by the local stream/message filters.
        return relayEventSubscriber.subscribe(
            RelayFilter(
                kinds = listOf(NostrEventKind.ChatMessage.value, NostrEventKind.Zap.value),
                addressTags = listOf(streamingNaddr.asATagValue()),
            ),
        ).map { event ->
            LiveFeedResponse(
                zaps = listOfNotNull(event.takeIf { it.kind == NostrEventKind.Zap.value }),
                chatMessages = listOfNotNull(event.takeIf { it.kind == NostrEventKind.ChatMessage.value }),
            )
        }
    }

    override suspend fun subscribeToLiveEventsFromFollows(userId: String): Flow<NostrEvent> {
        val followedPubkeys = relayEventSubscriber.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.FollowList.value),
                authors = listOf(userId),
                limit = 1,
            ),
        ).maxByOrNull { it.createdAt }?.tags?.pubkeyTagValues().orEmpty().distinct()
        if (followedPubkeys.isEmpty()) return emptyFlow()

        return relayEventSubscriber.subscribe(
            RelayFilter(
                kinds = listOf(NostrEventKind.LiveActivity.value),
                authors = followedPubkeys,
            ),
        )
    }

    override suspend fun getLiveEventsFromFollowsSnapshot(userId: String): List<NostrEvent> {
        val followedPubkeys = relayEventSubscriber.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.FollowList.value),
                authors = listOf(userId),
                limit = 1,
            ),
        ).maxByOrNull { it.createdAt }?.tags?.pubkeyTagValues().orEmpty().distinct()
        if (followedPubkeys.isEmpty()) return emptyList()

        return relayEventSubscriber.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.LiveActivity.value),
                authors = followedPubkeys,
                limit = (followedPubkeys.size * 4).coerceAtMost(500),
            ),
        ).filter { it.kind == NostrEventKind.LiveActivity.value }
    }

    override suspend fun findLiveStream(body: FindLiveStreamRequestBody): FindLiveStreamResponse {
        val liveActivity = relayEventSubscriber.query(
            RelayFilter(
                kinds = listOf(NostrEventKind.LiveActivity.value),
                authors = listOf(body.hostPubkey),
                limit = 50,
            ),
        ).firstOrNull { event ->
            event.tags.any { tag ->
                tag.firstOrNull()?.jsonPrimitive?.content == "d" &&
                    tag.getOrNull(1)?.jsonPrimitive?.content == body.identifier
            }
        }
        return FindLiveStreamResponse(
            liveActivity = liveActivity,
        )
    }
}
