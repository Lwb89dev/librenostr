package net.primal.android.thread.notes

import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import net.primal.domain.posts.immediateParentId
import net.primal.domain.posts.threadRootId
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
    fun `a very deep chain resolves without a stack overflow, one level per post`() {
        // A NIP-10 reply chain has no protocol-level depth limit. The walk must be iterative, not
        // one recursive call per level, or a long enough chain overflows the JVM call stack.
        val depth = 20_000
        val posts = (0 until depth).map { i ->
            reply(id = "p$i", to = if (i == 0) OPENED_NOTE else "p${i - 1}")
        }

        val levels = posts.buildReplyTree(rootAuthorId = null).associate { (post, level) -> post.eventId to level }

        levels.getValue("p0") shouldBe 1
        levels.getValue("p${depth - 1}") shouldBe depth
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
    fun `an orphan's placement is flagged as unresolved, unlike a genuine direct reply to root`() {
        val posts = listOf(
            reply(id = "orphan", to = "somewhere-else-entirely"),
            reply(id = "genuine", to = OPENED_NOTE),
        )

        val placements = posts.buildReplyTree(rootAuthorId = null, rootId = OPENED_NOTE)
            .associateBy { it.post.eventId }

        placements.getValue("orphan").let {
            it.level shouldBe 1
            it.hasKnownParent shouldBe false
        }
        placements.getValue("genuine").let {
            it.level shouldBe 1
            it.hasKnownParent shouldBe true
        }
    }

    @Test
    fun `a resolved child of a resolved child is never flagged as unresolved`() {
        val posts = listOf(
            reply(id = "r1", to = OPENED_NOTE),
            reply(id = "r1a", to = "r1"),
        )

        val placements = posts.buildReplyTree(rootAuthorId = null, rootId = OPENED_NOTE)
            .associateBy { it.post.eventId }

        placements.getValue("r1").hasKnownParent shouldBe true
        placements.getValue("r1a").hasKnownParent shouldBe true
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
    fun `siblings with equal timestamps break the tie by event id, not query order`() {
        // Two relays can hand back replies with the same created_at second — the order must not
        // depend on whichever happened to arrive first from the DB/relay.
        val posts = listOf(
            reply(id = "z", to = OPENED_NOTE, at = 5),
            reply(id = "a", to = OPENED_NOTE, at = 5),
            reply(id = "m", to = OPENED_NOTE, at = 5),
        )

        val order = posts.buildReplyTree(rootAuthorId = null).map { (post, _) -> post.eventId }

        order shouldBe listOf("a", "m", "z")
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

    @Test
    fun `a single legacy unmarked e tag is treated as the parent`() {
        // Deprecated NIP-10 form: no "reply"/"root" marker at all, just a bare ["e", id].
        val post = legacyReplyPost(id = "r1", eTags = listOf(OPENED_NOTE))

        val levels = listOf(post).buildReplyTree(rootAuthorId = null)
            .associate { (p, level) -> p.eventId to level }

        levels.getValue("r1") shouldBe 1
    }

    @Test
    fun `legacy unmarked e tags use the LAST one as the parent, not the first`() {
        // NIP-10 legacy convention: with multiple unmarked e tags, the first is the root and the
        // last is who this note actually replies to. "root-elsewhere" is deliberately absent from
        // this page — if the parser read the FIRST tag instead of the last, "b" would resolve to
        // that absent id and fall back to level 1, not to level 2 as a child of "a".
        val a = legacyReplyPost(id = "a", eTags = listOf("root-elsewhere"))
        val b = legacyReplyPost(id = "b", eTags = listOf("root-elsewhere", "a"))

        val levels = listOf(a, b).buildReplyTree(rootAuthorId = null)
            .associate { (post, level) -> post.eventId to level }

        levels.getValue("a") shouldBe 1
        levels.getValue("b") shouldBe 2 // parent is "a" (last e tag), not "root-elsewhere" (first)
    }

    @Test
    fun `a mention-marked e tag is excluded from the legacy fallback`() {
        // A mention-marked tag must never be read as the legacy "last e tag" parent — with no
        // other e tag present, this has no real parent and falls back to level 1 (direct reply to
        // what's open), not to the mentioned event.
        val post = FeedPost(
            eventId = "r1",
            author = FeedPostAuthor(authorId = "someone", handle = "someone", displayName = "Someone"),
            kind = 1,
            content = "",
            tags = listOf(eTag("mentioned-elsewhere", marker = "mention")),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "",
        )

        val levels = listOf(post).buildReplyTree(rootAuthorId = null)
            .associate { (p, level) -> p.eventId to level }
        levels.getValue("r1") shouldBe 1
    }

    @Test
    fun `a marked reply tag wins over legacy unmarked e tags when both are present`() {
        val post = FeedPost(
            eventId = "r1",
            author = FeedPostAuthor(authorId = "someone", handle = "someone", displayName = "Someone"),
            kind = 1,
            content = "",
            tags = listOf(
                eTag("legacy-parent", marker = ""),
                eTag("real-parent", marker = "reply"),
            ),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "",
        )
        val realParent = legacyReplyPost(id = "real-parent", eTags = emptyList())

        val order = listOf(realParent, post).buildReplyTree(rootAuthorId = null)

        val levels = order.associate { (p, level) -> p.eventId to level }
        levels.getValue("real-parent") shouldBe 1
        levels.getValue("r1") shouldBe 2 // parent is "real-parent", not "legacy-parent"
    }

    @Test
    fun `threadRootId reads the root-marked tag, distinct from the immediate parent`() {
        // C replies to B, which replies to A, which replies to the root — C's immediate parent is
        // B, but its root is still the original root, not B and not A.
        val c = FeedPost(
            eventId = "c",
            author = FeedPostAuthor(authorId = "someone", handle = "someone", displayName = "Someone"),
            kind = 1,
            content = "",
            tags = listOf(eTag(OPENED_NOTE, marker = "root"), eTag("b", marker = "reply")),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "",
        )

        c.threadRootId() shouldBe OPENED_NOTE
    }

    @Test
    fun `threadRootId falls back to the root-marked tag even for a direct reply to root`() {
        val rootOnlyReply = FeedPost(
            eventId = "r1",
            author = FeedPostAuthor(authorId = "someone", handle = "someone", displayName = "Someone"),
            kind = 1,
            content = "",
            tags = listOf(eTag(OPENED_NOTE, marker = "root")),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "",
        )

        // Same tag serves as both root and immediate parent for a direct reply to root.
        rootOnlyReply.threadRootId() shouldBe OPENED_NOTE
    }

    @Test
    fun `threadRootId uses the FIRST legacy unmarked e tag, the opposite end from immediateParentId`() {
        val post = legacyReplyPost(id = "c", eTags = listOf("the-root", "the-parent"))

        post.threadRootId() shouldBe "the-root"
    }

    // ------------------------------------------------------------------------------ NIP-22 (kind 1111)

    @Test
    fun `a NIP-22 top-level comment's lowercase e tag is both its parent and its root`() {
        val comment = commentOn(id = "c1", parent = "article-root", root = "article-root")

        comment.immediateParentId() shouldBe "article-root"
        comment.threadRootId() shouldBe "article-root"
    }

    @Test
    fun `a NIP-22 comment on a comment nests under its actual parent, not the root`() {
        val c1 = commentOn(id = "c1", parent = "article-root", root = "article-root")
        val c2 = commentOn(id = "c2", parent = "c1", root = "article-root")

        val levels = listOf(c1, c2).buildReplyTree(rootAuthorId = null)
            .associate { (post, level) -> post.eventId to level }

        levels.getValue("c1") shouldBe 1
        levels.getValue("c2") shouldBe 2
        c2.threadRootId() shouldBe "article-root" // root scope stays the same at every depth
    }

    @Test
    fun `a NIP-22 comment whose root is an addressable event uses the uppercase A tag`() {
        val comment = commentOn(id = "c1", parent = "article-root", root = "30023:pubkey:d-tag", rootIsATag = true)

        comment.threadRootId() shouldBe "30023:pubkey:d-tag"
    }

    /** A NIP-22 (kind 1111) comment: lowercase e = parent item, uppercase E = root scope. */
    private fun commentOn(id: String, parent: String, root: String, rootIsATag: Boolean = false) =
        FeedPost(
            eventId = id,
            author = FeedPostAuthor(authorId = "author-$id", handle = "author-$id", displayName = "author-$id"),
            kind = 1111,
            content = "",
            tags = listOf(
                if (rootIsATag) uppercaseATag(root) else uppercaseETag(root),
                eTag(parent, marker = ""),
            ),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "",
        )

    private fun uppercaseETag(eventId: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("E"))
            add(JsonPrimitive(eventId))
        }

    private fun uppercaseATag(coordinate: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("A"))
            add(JsonPrimitive(coordinate))
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

    /** A deprecated-NIP-10 post: one bare `["e", id]` per entry in [eTags], no marker at all. */
    private fun legacyReplyPost(id: String, eTags: List<String>, at: Long = 1) =
        FeedPost(
            eventId = id,
            author = FeedPostAuthor(authorId = "author-$id", handle = "author-$id", displayName = "author-$id"),
            kind = 1,
            content = "",
            tags = eTags.map { legacyETag(it) },
            timestamp = Instant.fromEpochSeconds(at),
            rawNostrEvent = "",
        )

    private fun legacyETag(eventId: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(eventId))
        }

    private companion object {
        const val OPENED_NOTE = "opened-note"
        const val AUTHOR = "root-author"
    }
}
