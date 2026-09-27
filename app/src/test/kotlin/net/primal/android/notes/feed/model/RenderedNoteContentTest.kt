package net.primal.android.notes.feed.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import io.kotest.matchers.shouldBe
import org.junit.Test

class RenderedNoteContentTest {

    private val color = Color.Red

    private fun rendered(text: String, annotations: List<ContentAnnotation>, shouldEllipsize: Boolean = false) =
        RenderedNoteContent(refinedText = text, shouldEllipsize = shouldEllipsize, annotations = annotations)

    @Test
    fun `a plain hashtag with no known suffix renders unchanged, just styled`() {
        val content = rendered(
            text = "gm #friends today",
            annotations = listOf(
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#friends", start = 3, end = 11),
            ),
        )

        val result = content.toAnnotatedString(seeMoreText = "see more", highlightColor = color)

        result.text shouldBe "gm #friends today"
        result.spanStyles.single { it.item == SpanStyle(color = color) }.let {
            it.start shouldBe 3
            it.end shouldBe 11
        }
    }

    @Test
    fun `a known hashtag gets its brand-color suffix appended right after it, outside the clickable span`() {
        val content = rendered(
            text = "stacking #Bitcoin daily",
            annotations = listOf(
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#Bitcoin", start = 9, end = 17),
            ),
        )

        val result = content.toAnnotatedString(seeMoreText = "see more", highlightColor = color)

        result.text shouldBe "stacking #Bitcoin 🟠 daily"
        // The suffix sits after the annotation's own end, so tapping/copying the hashtag itself is unaffected.
        val hashtagAnnotation = result.getStringAnnotations(HASHTAG_ANNOTATION_TAG, 0, result.length).single()
        result.text.substring(hashtagAnnotation.start, hashtagAnnotation.end) shouldBe "#Bitcoin"
    }

    @Test
    fun `the community hashtags get the purple circle, matching bitcoin's orange treatment`() {
        listOf("#nostr", "#grownostr", "#asknostr").forEach { tag ->
            val content = rendered(
                text = "gm $tag",
                annotations = listOf(
                    ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = tag, start = 3, end = 3 + tag.length),
                ),
            )

            content.toAnnotatedString(seeMoreText = "see more", highlightColor = color).text shouldBe
                "gm $tag 🟣"
        }
    }

    @Test
    fun `the suffix is not appended for a hashtag that is not in the known list`() {
        val content = rendered(
            text = "#bitcoinsomethingelse is not bitcoin",
            annotations = listOf(
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#bitcoinsomethingelse", start = 0, end = 21),
            ),
        )

        content.toAnnotatedString(seeMoreText = "see more", highlightColor = color).text shouldBe
            "#bitcoinsomethingelse is not bitcoin"
    }

    @Test
    fun `two occurrences of a known hashtag each get their own suffix, and later offsets still line up`() {
        val text = "#bitcoin then more #bitcoin talk"
        val content = rendered(
            text = text,
            annotations = listOf(
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#bitcoin", start = 0, end = 8),
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#bitcoin", start = 19, end = 27),
            ),
        )

        val result = content.toAnnotatedString(seeMoreText = "see more", highlightColor = color)

        result.text shouldBe "#bitcoin 🟠 then more #bitcoin 🟠 talk"
        result.spanStyles.count { it.item == SpanStyle(color = color) } shouldBe 2
    }

    @Test
    fun `an unrelated annotation after a suffixed hashtag still lands on the right characters`() {
        // Regression check for the rewrite: every offset is the builder's own running length, not an
        // index into the original string, so a profile mention after a hashtag must not drift.
        val text = "#bitcoin fan, ask @npub1abc"
        val content = rendered(
            text = text,
            annotations = listOf(
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#bitcoin", start = 0, end = 8),
                ContentAnnotation(tag = PROFILE_ID_ANNOTATION_TAG, item = "hex-pubkey", start = 18, end = 27),
            ),
        )

        val result = content.toAnnotatedString(seeMoreText = "see more", highlightColor = color)

        val profileAnnotation = result.getStringAnnotations(PROFILE_ID_ANNOTATION_TAG, 0, result.length).single()
        result.text.substring(profileAnnotation.start, profileAnnotation.end) shouldBe "@npub1abc"
    }

    @Test
    fun `the see-more highlight still lands on the trailing text after a suffix shifted everything`() {
        val content = rendered(
            text = "#bitcoin " + "x".repeat(ELLIPSIZE_THRESHOLD),
            annotations = listOf(
                ContentAnnotation(tag = HASHTAG_ANNOTATION_TAG, item = "#bitcoin", start = 0, end = 8),
            ),
            shouldEllipsize = true,
        )

        val result = content.toAnnotatedString(seeMoreText = "see more", highlightColor = color)

        result.text.endsWith("see more") shouldBe true
        val seeMoreStyle = result.spanStyles.last()
        result.text.substring(seeMoreStyle.start, seeMoreStyle.end) shouldBe "see more"
    }
}
