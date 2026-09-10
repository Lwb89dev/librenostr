package net.primal.data.repository.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import net.primal.domain.posts.ThreadRelation

/**
 * Regression cover for the reason a sent private reply was invisible in its own thread.
 *
 * A gift-wrapped reply carries no public tags — its thread links live inside the encrypted rumor
 * and reach the app as a normalized [ThreadRelation]. [performTopologicalSort] used to read tags
 * only, so such a post contributed no edge at all and, being visited last, was prepended to the
 * front of the list. The thread screen splits the sorted conversation at the opened note and
 * renders everything before it as the ancestor breadcrumb, so the reply was drawn above the
 * conversation root instead of under the note it answered — off-screen, and reading as lost.
 */
class PrivateReplyThreadOrderTest {

    @Test
    fun `a private reply carrying only a thread relation is ordered after its parent`() {
        val sorted = listOf(root(), publicReply(), privateReply()).performTopologicalSort()

        assertEquals(listOf(ROOT_ID, PUBLIC_REPLY_ID, PRIVATE_REPLY_ID), sorted.map { it.eventId })
    }

    @Test
    fun `a private reply never sorts ahead of the conversation root`() {
        val sorted = listOf(root(), privateReply()).performTopologicalSort()

        assertEquals(ROOT_ID, sorted.first().eventId)
    }

    @Test
    fun `public replies keep the order their tags describe`() {
        val sorted = listOf(publicReply(), root()).performTopologicalSort()

        assertEquals(listOf(ROOT_ID, PUBLIC_REPLY_ID), sorted.map { it.eventId })
    }

    /** A relationship graph that cannot be sorted falls back to the input order, never throws. */
    @Test
    fun `a cycle falls back to the original order`() {
        val a = post(eventId = ROOT_ID, relation = ThreadRelation(ROOT_ID, ROOT_ID, PUBLIC_REPLY_ID))
        val b = post(eventId = PUBLIC_REPLY_ID, relation = ThreadRelation(PUBLIC_REPLY_ID, ROOT_ID, ROOT_ID))

        val sorted = listOf(a, b).performTopologicalSortOrThis()

        assertEquals(listOf(ROOT_ID, PUBLIC_REPLY_ID), sorted.map { it.eventId })
    }

    private fun root() = post(eventId = ROOT_ID)

    private fun publicReply() =
        post(
            eventId = PUBLIC_REPLY_ID,
            tags = listOf(eventTag(ROOT_ID, "root")),
        )

    private fun privateReply() =
        post(
            eventId = PRIVATE_REPLY_ID,
            relation = ThreadRelation(
                eventId = PRIVATE_REPLY_ID,
                rootId = ROOT_ID,
                parentId = PUBLIC_REPLY_ID,
            ),
            isPrivate = true,
        )

    private fun post(
        eventId: String,
        tags: List<JsonArray> = emptyList(),
        relation: ThreadRelation? = null,
        isPrivate: Boolean = false,
    ) = FeedPost(
        eventId = eventId,
        author = FeedPostAuthor(authorId = AUTHOR, handle = "author", displayName = "author"),
        kind = NostrEventKind.ShortTextNote.value,
        content = "content",
        tags = tags,
        timestamp = Instant.fromEpochSeconds(1),
        rawNostrEvent = "",
        threadRelation = relation,
        isPrivate = isPrivate,
    )

    private fun eventTag(id: String, marker: String) =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(id))
            add(JsonPrimitive(""))
            add(JsonPrimitive(marker))
        }

    private companion object {
        val ROOT_ID = "1".repeat(64)
        val PUBLIC_REPLY_ID = "2".repeat(64)
        val PRIVATE_REPLY_ID = "3".repeat(64)
        val AUTHOR = "a".repeat(64)
    }
}
