package net.primal.android.thread.notes

import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import org.junit.Test

/**
 * Pins that the thread screen's transform actually reaches [buildReplyTree], not just that the
 * tree algorithm is correct in isolation. A ViewModel test would need to stand up the whole
 * screen's Hilt graph to prove the same thing this does directly.
 */
class DisplayOrderedFeedPostUiTest {

    @Test
    fun `a reply to a reply comes out one level deeper than a direct reply`() {
        val posts = listOf(
            post(id = ROOT, to = null, at = 1),
            post(id = OPENED, to = ROOT, at = 2),
            post(id = "direct-reply", to = OPENED, at = 3),
            post(id = "nested-reply", to = "direct-reply", at = 4),
        )

        val result = posts.asDisplayOrderedFeedPostUi(highlightPostId = OPENED)

        result.map { it.postId } shouldBe listOf(ROOT, OPENED, "direct-reply", "nested-reply")
        result.first { it.postId == "direct-reply" }.replyLevel shouldBe 1
        result.first { it.postId == "nested-reply" }.replyLevel shouldBe 2
    }

    @Test
    fun `the ancestor chain keeps a flat level regardless of how deep it actually is`() {
        // The ancestor breadcrumb is rendered as a straight line above the opened note, not as
        // part of the reply tree, so it must not pick up a level from this transform.
        val posts = listOf(
            post(id = "grandparent", to = null, at = 1),
            post(id = "parent", to = "grandparent", at = 2),
            post(id = OPENED, to = "parent", at = 3),
        )

        val result = posts.asDisplayOrderedFeedPostUi(highlightPostId = OPENED)

        result.map { it.replyLevel } shouldBe listOf(0, 0, 0)
    }

    // ------------------------------------------------------------------------------- harness

    private fun post(id: String, to: String?, at: Long) =
        FeedPost(
            eventId = id,
            author = FeedPostAuthor(authorId = "author-$id", handle = id, displayName = id),
            kind = 1,
            content = "",
            tags = to?.let {
                listOf(
                    buildJsonArray {
                        add(JsonPrimitive("e"))
                        add(JsonPrimitive(it))
                        add(JsonPrimitive(""))
                        add(JsonPrimitive("reply"))
                    },
                )
            }.orEmpty(),
            timestamp = Instant.fromEpochSeconds(at),
            rawNostrEvent = "",
        )

    private companion object {
        const val ROOT = "root"
        const val OPENED = "opened"
    }
}
