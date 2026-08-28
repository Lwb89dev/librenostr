package net.primal.data.repository.feed

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.hasEventIdTag
import net.primal.domain.nostr.pubkeyTagValues

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
}
