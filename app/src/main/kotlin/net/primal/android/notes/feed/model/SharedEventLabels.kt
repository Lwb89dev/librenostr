package net.primal.android.notes.feed.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import net.primal.android.R
import net.primal.domain.links.EventUriNostrType

/**
 * Replaces every shared event in a one-line preview with a short label for what it is.
 *
 * A preview is one or two lines of plain text with no room for the note or article a message or
 * post shares, and it used to keep the reference as typed, so a conversation whose last message
 * was a shared note read `nostr:nevent1qqs…` followed by a hundred more characters of bech32.
 * Saying that it is a note is what the line can actually usefully carry.
 *
 * Profile mentions are left alone: they are replaced by the person's handle further down, which
 * already reads naturally in a sentence.
 */
internal fun String.replaceSharedEventsWithLabels(
    nostrUris: List<NoteNostrUriUi>,
    labelFor: (EventUriNostrType) -> String,
): String =
    nostrUris
        .filter { it.type != EventUriNostrType.Profile }
        .fold(initial = this) { text, nostrUri -> text.replace(nostrUri.uri, labelFor(nostrUri.type)) }

/** The label to show for a shared event of each type, resolved once per composition. */
@Composable
internal fun rememberSharedEventLabels(): (EventUriNostrType) -> String {
    val noteLabel = stringResource(id = R.string.shared_event_label_note)
    val articleLabel = stringResource(id = R.string.shared_event_label_article)
    val eventLabel = stringResource(id = R.string.shared_event_label_event)
    return remember(noteLabel, articleLabel, eventLabel) {
        { type ->
            when (type) {
                EventUriNostrType.Note -> noteLabel
                EventUriNostrType.Article -> articleLabel
                else -> eventLabel
            }
        }
    }
}
