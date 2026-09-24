package net.primal.android.notes.feed.model

import io.kotest.matchers.shouldBe
import net.primal.domain.links.EventUriNostrType
import org.junit.Test

class SharedEventLabelsTest {

    private val labels: (EventUriNostrType) -> String = {
        when (it) {
            EventUriNostrType.Note -> "[Note]"
            EventUriNostrType.Article -> "[Article]"
            else -> "[Event]"
        }
    }

    @Test
    fun replaceSharedEventsWithLabels_labelsASharedNoteInsteadOfShowingItsHash() {
        val uri = "nostr:nevent1qqsxc3v6yxnymuf03fmru5n9yenq5yxyjzj6spwgj46uw08qxc4khacppemhxue69uhkummn9ekx7mp0"

        val result = "look at this $uri".replaceSharedEventsWithLabels(
            nostrUris = listOf(nostrUri(uri, EventUriNostrType.Note)),
            labelFor = labels,
        )

        result shouldBe "look at this [Note]"
    }

    @Test
    fun replaceSharedEventsWithLabels_usesTheTypeOfEachReference() {
        val note = "nostr:note1aaaa"
        val article = "nostr:naddr1bbbb"
        val unknown = "nostr:nevent1cccc"

        val result = "$note $article $unknown".replaceSharedEventsWithLabels(
            listOf(
                nostrUri(note, EventUriNostrType.Note),
                nostrUri(article, EventUriNostrType.Article),
                nostrUri(unknown, EventUriNostrType.Unsupported),
            ),
            labels,
        )

        result shouldBe "[Note] [Article] [Event]"
    }

    @Test
    fun replaceSharedEventsWithLabels_leavesProfileMentionsForTheHandleReplacement() {
        val mention = "nostr:npub1dddd"

        val result = "hi $mention".replaceSharedEventsWithLabels(
            nostrUris = listOf(nostrUri(mention, EventUriNostrType.Profile)),
            labelFor = labels,
        )

        result shouldBe "hi $mention"
    }

    @Test
    fun replaceSharedEventsWithLabels_leavesTextWithoutReferencesUntouched() {
        "just words".replaceSharedEventsWithLabels(emptyList(), labels) shouldBe "just words"
    }

    private fun nostrUri(uri: String, type: EventUriNostrType) =
        NoteNostrUriUi(
            uri = uri,
            type = type,
            referencedEventAlt = null,
            referencedHighlight = null,
            referencedNote = null,
            referencedArticle = null,
            referencedUser = null,
            referencedZap = null,
            position = 0,
        )
}
