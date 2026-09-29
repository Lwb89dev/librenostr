package net.primal.android.deck

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import net.primal.android.navigation.navigateToNoteEditor
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.thread.notes.ThreadContract
import net.primal.android.thread.notes.ThreadScreen
import net.primal.android.thread.notes.ThreadViewModel

/**
 * The full [ThreadScreen] (its own Scaffold, top bar, reply composer and all) reused as-is inside
 * a deck column — same trick as [DeckMessagesColumn] for the message list. [onBack] returns to
 * the column's feed instead of popping the root NavController, so the column stays put.
 */
@Composable
fun DeckThreadContent(
    navController: NavController,
    noteId: String,
    noteCallbacks: NoteCallbacks,
    onBack: () -> Unit,
) {
    val viewModel = hiltViewModel<ThreadViewModel, ThreadViewModel.Factory>(
        key = "ThreadViewModel_$noteId",
    ) { factory -> factory.create(noteId = noteId) }

    ThreadScreen(
        viewModel = viewModel,
        callbacks = ThreadContract.ScreenCallbacks(
            onClose = onBack,
            onExpandReply = { args -> navController.navigateToNoteEditor(args) },
        ),
        noteCallbacks = noteCallbacks,
    )
}
