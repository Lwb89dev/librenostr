package net.primal.data.repository.notifications

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.notifications.NotificationGroup

class RelayNotificationsFetcherTest {

    private val alice = "alice"
    private val bob = "bob"
    private val carol = "carol"
    private val myNoteId = "1".repeat(64)
    private val quotedId = "2".repeat(64)

    private fun eTag(eventId: String) = buildJsonArray { add(JsonPrimitive("e")); add(JsonPrimitive(eventId)) }
    private fun pTag(pubkey: String) = buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(pubkey)) }

    private fun note(id: String, pubkey: String, content: String, createdAt: Long) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = createdAt,
        kind = NostrEventKind.ShortTextNote.value,
        tags = emptyList(),
        content = content,
        sig = "sig",
    )

    /** [alice]'s own note, quoting one of [carol]'s — the fixture every test below shares. */
    private fun myNote() = note(myNoteId, alice, "check this nostr:${quotedId.hexToNoteHrp()}", createdAt = 5)

    private fun quotedNote() = note(quotedId, carol, "hi", createdAt = 1)

    /** [bob] liking [alice]'s note — a YOUR_POST_WAS_LIKED notification. */
    private fun reactionFromBob() = NostrEvent(
        id = "reaction1",
        pubKey = bob,
        createdAt = 10,
        kind = NostrEventKind.Reaction.value,
        tags = listOf(eTag(myNoteId), pTag(alice)),
        content = "+",
        sig = "sig",
    )

    @Test
    fun fetchEvents_neverQueriesMetadata_onlyFetchMetadataForDoes() =
        runTest {
            val querier = FakeQuerier(listOf(reactionFromBob(), myNote(), quotedNote()))
            val fetcher = RelayNotificationsFetcher(querier)

            val events = fetcher.fetchEvents(userId = alice, group = NotificationGroup.ALL, limit = 20)

            events.notifications.map { it.actionUserId } shouldBe listOf(bob)
            // The reacted-to note and the note it quotes are both content, resolved before any
            // metadata — the fixture's whole point is that this must not need a single kind=0.
            events.contentEvents.map { it.id }.toSet() shouldBe setOf(myNoteId, quotedId)
            events.actors shouldBe listOf(bob)
            events.quotedAuthors shouldBe listOf(carol)
            querier.requestedMetadataAuthors() shouldBe emptySet()

            val metadata = fetcher.fetchMetadataFor(events)
            metadata shouldBe emptyList() // FakeQuerier holds no kind-0 events; the point is what was asked for:
            querier.requestedMetadataAuthors() shouldBe setOf(bob, carol)
        }

    @Test
    fun fetch_compatibilityWrapper_returnsTheSameEventsAndMetadataRequests() =
        runTest {
            val querier = FakeQuerier(listOf(reactionFromBob(), myNote(), quotedNote()))
            val fetcher = RelayNotificationsFetcher(querier)

            val result = fetcher.fetch(userId = alice, group = NotificationGroup.ALL, limit = 20)

            result.notifications.map { it.actionUserId } shouldBe listOf(bob)
            result.feedResponse.notes.map { it.id }.toSet() shouldBe setOf(myNoteId, quotedId)
            querier.requestedMetadataAuthors() shouldBe setOf(bob, carol)
        }

    private class FakeQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        val requestedFilters = mutableListOf<RelayFilter>()

        fun requestedMetadataAuthors(): Set<String> =
            requestedFilters
                .filter { it.kinds == listOf(NostrEventKind.Metadata.value) }
                .flatMap { it.authors.orEmpty() }
                .toSet()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            requestedFilters += filter
            val matched = events.filter { event -> matches(event, filter) }
            return filter.limit?.let { matched.take(it) } ?: matched
        }

        private fun matches(event: NostrEvent, filter: RelayFilter): Boolean {
            filter.ids?.let { if (event.id !in it) return false }
            filter.authors?.let { if (event.pubKey !in it) return false }
            filter.kinds?.let { if (event.kind !in it) return false }
            filter.pubkeyTags?.let { wanted ->
                if (event.tags.pubkeyTagValues().none { it in wanted }) return false
            }
            return true
        }
    }
}
