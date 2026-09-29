package net.primal.android.deck

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import net.primal.android.messages.chat.ChatScreen
import net.primal.android.messages.chat.ChatViewModel
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks

/** The full [ChatScreen] reused as-is inside a deck column, same trick as [DeckThreadContent]. */
@Composable
fun DeckChatContent(participantId: String, noteCallbacks: NoteCallbacks, onBack: () -> Unit) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(
        key = "ChatViewModel_$participantId",
    ) { factory -> factory.create(participantId = participantId) }

    ChatScreen(viewModel = viewModel, onClose = onBack, noteCallbacks = noteCallbacks)
}
