package net.primal.android.settings.muted.tabs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import net.primal.android.notes.feed.list.NoteFeedList
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks

@Composable
fun MuteThreads(
    defaultMuteThreadsFeedSpec: String,
    noteCallbacks: NoteCallbacks,
    paddingValues: PaddingValues = PaddingValues(all = 0.dp),
) {
    NoteFeedList(
        feedSpec = defaultMuteThreadsFeedSpec,
        noteCallbacks = noteCallbacks,
        contentPadding = paddingValues,
        pollingEnabled = false,
        allowMutedThreads = true,
    )
}
