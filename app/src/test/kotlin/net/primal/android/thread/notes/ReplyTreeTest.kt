package net.primal.android.thread.notes

import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import org.junit.Test

/**
 * The thread screen used to sort every reply after the opened note by timestamp alone, in two
 * flat buckets — the thread author, then everyone else — with no notion of who was replying to
 * whom. A reply to a reply rendered identically to a reply to the opened note itself. These pin
 * the replacement: a reply's NIP-10 `e` tag is exact, so the tree it describes should be exact
 * too, not approximated by arrival time.
 */
class ReplyTreeTest {

    @Test
    fun `a reply to a reply is one level deeper than its parent`() {
        val posts = listOf(
            reply(id = "r1", to = OPENED_NOTE),
            reply(id = "r1a", to = "r1"),
        )

        val levels = posts.buildReplyTree(rootAuthorId = null).associate { (post, level) -> post.eventId to level }

        levels.getValue("r1") shouldBe 1 // a direct reply to the opened note is level 1
        levels.getValue("r1a") shouldBe 2 // a reply to that reply is one level deeper
    }

    @Test
    fun `three generations of replies each go one level deeper than the last`() {
        val posts = listOf(
            reply(id = "r1", to = OPENED_NOTE),
            reply(id = "r1a", to = "r1"),
            reply(id = "r1a1", to = "r1a"),
        )

        val levels = posts.buildReplyTree(rootAuthorId = null).associate { (post, level) -> post.eventId to level }

        listOf("r1", "r1a", "r1a1").map { levels.getValue(it) } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a sub-thread's replies are grouped immediately after it, before the next sibling`() {
        // The regression this exists for: two direct replies to the opened note, one of them
        // with its own sub-thread. A flat sort by time could put the second top-level reply
        // between a sub-reply and its parent.
        // r1 is the newer of the two top-level replies, so "newest first" alone would already
        // put it ahead of r2 — the property this pins is that r1a stays glued to r1 rather than
        // sorting by its own timestamp among all replies regardless of depth.
        val posts = listOf(
            reply(id = "r2", to = OPENED_NOTE, at = 1),
            reply(id = "r1", to = OPENED_NOTE, at = 3),
            reply(id = "r1a", to = "r1", at = 2),
        )

        val order = posts.buildReplyTree(rootAuthorId = null).map { (post, _) -> post.eventId }

        order shouldBe listOf("r1", "r1a", "r2")
    }

    @Test
    fun `a reply whose parent is missing from this page is treated as a direct reply`() {
        // The parent could be the opened note, an ancestor, or something outside this fetch
        // entirely — in every case, nothing here is a legitimate root of its own, and it should
        // read as "replying to what you opened," not vanish or crash.
        val posts = listOf(reply(id = "orphan", to = "somewhere-else-entirely"))

        val levels = posts.buildReplyTree(rootAuthorId = null).associate { (post, level) -> post.eventId to level }

        levels.getValue("orphan") shouldBe 1
    }

    @Test
    fun `a two-reply cycle terminates and keeps both posts instead of dropping them`() {
        // Malformed input — two replies each naming the other as parent — must not hang the
        // walk, and must not make either post vanish either: a broken tag is a worse reason to
        // lose someone's reply than the flat, unnested rendering this whole tree replaces.
        val posts = listOf(
            reply(id = "a", to = "b"),
            reply(id = "b", to = "a"),
        )

        val order = posts.buildReplyTree(rootAuthorId = null).map { (post, _) -> post.eventId }

        order.toSet() shouldBe setOf("a", "b")
    }

    @Test
    fun `the thread author's own replies come first, oldest to newest`() {
        val posts = listOf(
            reply(id = "later", to = OPENED_NOTE, at = 20, author = AUTHOR),
            reply(id = "earlier", to = OPENED_NOTE, at = 10, author = AUTHOR),
            reply(id = "stranger", to = OPENED_NOTE, at = 15, author = "someone-else"),
        )

        val order = posts.buildReplyTree(rootAuthorId = AUTHOR).map { (post, _) -> post.eventId }

        order shouldBe listOf("earlier", "later", "stranger")
    }

    @Test
    fun `everyone else's replies sort newest first`() {
        val posts = listOf(
            reply(id = "older", to = OPENED_NOTE, at = 10),
            reply(id = "newer", to = OPENED_NOTE, at = 20),
        )

        val order = posts.buildReplyTree(rootAuthorId = null).map { (post, _) -> post.eventId }

        order shouldBe listOf("newer", "older")
    }

    @Test
    fun `a reply directly to the root omits the reply marker, and that still resolves`() {
        // NIP-10 allows a reply to the root itself to carry only a "root"-marked e tag, with no
        // "reply"-marked one — the two are not required to coexist.
        val rootOnlyReply = FeedPost(
            eventId = "r1",
            author = FeedPostAuthor(authorId = "someone", handle = "someone", displayName = "Someone"),
            kind = 1,
            content = "",
            tags = listOf(eTag(OPENED_NOTE, marker = "root")),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "",
        )

        val levels = listOf(rootOnlyReply).buildReplyTree(rootAuthorId = null)
            .associate { (post, level) -> post.eventId to level }

        levels.getValue("r1") shouldBe 1
    }

    // ------------------------------------------------------------------------------- harness

    private fun reply(id: String, to: String, at: Long = 1, author: String = "author-$id") =
        FeedPost(
            eventId = id,
            author = FeedPostAuthor(authorId = author, handle = author, displayName = author),
            kind = 1,
            content = "",
            tags = listOf(eTag(to, marker = "reply")),
            timestamp = Instant.fromEpochSeconds(at),
            rawNostrEvent = "",
        )

    private fun eTag(eventId: String, marker: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(eventId))
            add(JsonPrimitive(""))
            add(JsonPrimitive(marker))
        }

    private companion object {
        const val OPENED_NOTE = "opened-note"
        const val AUTHOR = "root-author"
    }
}
