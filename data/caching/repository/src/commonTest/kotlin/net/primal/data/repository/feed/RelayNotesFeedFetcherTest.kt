package net.primal.data.repository.feed

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.hasEventIdTag
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

class RelayNotesFeedFetcherTest {

    private fun event(
        id: String,
        pubkey: String,
        kind: Int,
        createdAt: Long,
        tags: List<kotlinx.serialization.json.JsonArray> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = "hello",
        sig = "sig",
    )

    @Test
    fun pubkeyTagValues_readsPTags() {
        val tags = listOf(
            buildJsonArray {
                add(JsonPrimitive("p"))
                add(JsonPrimitive("alice"))
            },
            buildJsonArray {
                add(JsonPrimitive("e"))
                add(JsonPrimitive("note"))
            },
            buildJsonArray {
                add(JsonPrimitive("p"))
                add(JsonPrimitive("bob"))
            },
        )
        tags.pubkeyTagValues() shouldBe listOf("alice", "bob")
    }

    @Test
    fun hasEventIdTag_detectsReplies() {
        val reply = listOf(
            buildJsonArray {
                add(JsonPrimitive("e"))
                add(JsonPrimitive("root"))
            },
        )
        reply.hasEventIdTag() shouldBe true
        emptyList<kotlinx.serialization.json.JsonArray>().hasEventIdTag() shouldBe false
    }

    @Test
    fun toFeedResponse_splitsNotesAndRepostsAndPagesByCreatedAt() {
        val note = event("n1", "alice", NostrEventKind.ShortTextNote.value, 20)
        val older = event("n0", "bob", NostrEventKind.ShortTextNote.value, 10)
        val repost = event("r1", "carol", NostrEventKind.ShortTextNoteRepost.value, 15)
        val response = listOf(note, older, repost).toFeedResponse(metadata = emptyList())

        response.notes.map { it.id } shouldBe listOf("n1", "n0")
        response.reposts.map { it.id } shouldBe listOf("r1")
        response.paging?.sinceId shouldBe 10
        response.paging?.untilId shouldBe 20
        response.paging?.elements shouldBe listOf("n1", "n0", "r1")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fetch_quotedNoteInContent_isFetchedAndReturnedAsReferencedEvent() =
        runTest {
            val quotedId = "b".repeat(64)
            val quoted = event(quotedId, "carol", NostrEventKind.ShortTextNote.value, 5)
            val page = event(
                id = "n1",
                pubkey = "alice",
                kind = NostrEventKind.ShortTextNote.value,
                createdAt = 20,
            ).copy(content = "check this out nostr:${quotedId.hexToNoteHrp()}")

            val querier = FakeQuerier(listOf(page, quoted))
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            )

            val response = RelayNotesFeedFetcher(querier = querier, coordinator = coordinator).fetch(
                userId = "alice",
                feedSpec = """{"id":"feed","kind":"notes","notes":"authored","pubkey":"alice"}""",
                includeReplies = false,
                limit = 20,
            )

            response.referencedEvents.map { it.id } shouldBe listOf(quotedId)
        }

    private class FakeQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val matched = events.filter { event -> matches(event, filter) }
            return filter.limit?.let { matched.take(it) } ?: matched
        }

        private fun matches(event: NostrEvent, filter: RelayFilter): Boolean {
            val ids = filter.ids
            if (ids != null && event.id !in ids) return false
            val authors = filter.authors
            if (authors != null && event.pubKey !in authors) return false
            val kinds = filter.kinds
            if (kinds != null && event.kind !in kinds) return false
            return true
        }
    }
}
