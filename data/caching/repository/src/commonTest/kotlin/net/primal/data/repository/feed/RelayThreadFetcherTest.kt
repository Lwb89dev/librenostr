package net.primal.data.repository.feed

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findRootEventId
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

class RelayThreadFetcherTest {

    private val noteKind = listOf(NostrEventKind.ShortTextNote.value)

    @Test
    fun fetch_includesRootAndDirectReplies() = runTest {
        val root = note("root", "alice", 10)
        val reply = replyTo("r1", "bob", 20, rootId = "root")
        val other = note("other", "carol", 30)
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, reply, other)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "r1")
    }

    @Test
    fun fetch_fromReply_walksAncestorsAndLoadsRootThread() = runTest {
        val root = note("root", "alice", 10)
        val child = replyTo("child", "bob", 20, rootId = "root")
        val grand = replyTo("grand", "carol", 30, rootId = "root", replyId = "child")
        val sibling = replyTo("sib", "dave", 25, rootId = "root")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, child, grand, sibling)))
            .fetch(noteId = "grand", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "child", "grand", "sib")
    }

    @Test
    fun fetch_dedupesDuplicateEvents() = runTest {
        val root = note("root", "alice", 10)
        val reply = replyTo("r1", "bob", 20, rootId = "root")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, reply, root, reply)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldBe listOf("root", "r1")
    }

    @Test
    fun fetch_missingAncestor_returnsKnownEvents() = runTest {
        val root = note("root", "alice", 10)
        val grand = replyTo("grand", "carol", 30, rootId = "root", replyId = "missing")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, grand)))
            .fetch(noteId = "grand", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "grand")
    }

    @Test
    fun fetch_outOfOrderReplies_allPresent() = runTest {
        val root = note("root", "alice", 10)
        val late = replyTo("late", "bob", 40, rootId = "root")
        val early = replyTo("early", "carol", 15, rootId = "root")
        val mid = replyTo("mid", "dave", 25, rootId = "root")
        val response = RelayThreadFetcher(FakeQuerier(listOf(late, root, mid, early)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder listOf("root", "late", "early", "mid")
    }

    @Test
    fun fetch_quotedNoteInContent_isFetchedAndReturnedAsReferencedEvent() = runTest {
        val quotedId = "b".repeat(64)
        val quoted = note(quotedId, "carol", 5)
        val root = note("root", "alice", 10).copy(content = "check this out nostr:${quotedId.hexToNoteHrp()}")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, quoted)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.referencedEvents.map { it.id } shouldBe listOf(quotedId)
    }

    @Test
    fun fetch_quoteTag_isFetchedAndReturnedAsReferencedEvent() = runTest {
        val quotedId = "c".repeat(64)
        val quoted = note(quotedId, "carol", 5)
        val root = note("root", "alice", 10).copy(tags = listOf(qTag(quotedId)))
        val response = RelayThreadFetcher(FakeQuerier(listOf(root, quoted)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.referencedEvents.map { it.id } shouldBe listOf(quotedId)
    }

    @Test
    fun fetch_quotedNoteNeverArrives_stillReturnsWhatItHas() = runTest {
        val missingId = "d".repeat(64)
        val root = note("root", "alice", 10).copy(content = "nostr:${missingId.hexToNoteHrp()}")
        val response = RelayThreadFetcher(FakeQuerier(listOf(root)))
            .fetch(noteId = "root", kinds = noteKind, limit = 50)

        response.notes.map { it.id } shouldBe listOf("root")
        response.referencedEvents shouldBe emptyList()
    }

    @Test
    fun findRootEventId_prefersRootMarker() {
        val tags = listOf(
            eTag("mention", marker = "mention"),
            eTag("root-id", marker = "root"),
            eTag("reply-id", marker = "reply"),
        )
        tags.findRootEventId() shouldBe "root-id"
        emptyList<kotlinx.serialization.json.JsonArray>().findRootEventId() shouldBe null
    }

    @Test
    fun toThreadFeedResponse_bucketsKinds() {
        val note = note("n1", "alice", 10)
        val poll = note("p1", "bob", 11).copy(kind = NostrEventKind.Poll.value)
        val zap = note("z1", "carol", 12).copy(kind = NostrEventKind.Zap.value)
        val response = listOf(note, poll, zap).toThreadFeedResponse(metadata = emptyList())

        response.notes.map { it.id } shouldBe listOf("n1")
        response.polls.map { it.id } shouldBe listOf("p1")
        response.zaps.map { it.id } shouldBe listOf("z1")
    }

    private fun note(id: String, pubkey: String, createdAt: Long) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = createdAt,
        kind = NostrEventKind.ShortTextNote.value,
        tags = emptyList(),
        content = "hello",
        sig = "sig",
    )

    private fun replyTo(
        id: String,
        pubkey: String,
        createdAt: Long,
        rootId: String,
        replyId: String? = null,
    ): NostrEvent {
        val tags = buildList {
            add(eTag(rootId, marker = "root"))
            if (replyId != null) add(eTag(replyId, marker = "reply"))
        }
        return note(id, pubkey, createdAt).copy(tags = tags)
    }

    private fun eTag(eventId: String, marker: String) = buildJsonArray {
        add(JsonPrimitive("e"))
        add(JsonPrimitive(eventId))
        add(JsonPrimitive(""))
        add(JsonPrimitive(marker))
    }

    private fun qTag(eventId: String) = buildJsonArray {
        add(JsonPrimitive("q"))
        add(JsonPrimitive(eventId))
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
            val eventTags = filter.eventTags
            if (eventTags != null && event.tags.eventIdTagValues().none { it in eventTags }) return false
            val since = filter.since
            if (since != null && event.createdAt < since) return false
            val until = filter.until
            if (until != null && event.createdAt > until) return false
            return true
        }
    }
}
