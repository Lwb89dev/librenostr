package net.primal.android.deck

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import net.primal.android.messages.conversation.MessageConversationListContract
import net.primal.android.messages.conversation.MessageConversationListViewModel
import net.primal.android.messages.conversation.MessageListScreen
import net.primal.android.navigation.navigateToNewMessage

/**
 * [MessageListScreen] brings its own top bar (title + back arrow), so this column has none of
 * its own — the back arrow is wired to remove the column instead of navigating away, which is
 * the natural way to "close" a persistent panel rather than a full-screen destination.
 *
 * Opening a conversation or a profile stays in-deck ([onConversationOpen]/[onProfileOpen] flip
 * local state in [DeckColumnView]); starting a brand new message is a short modal-style flow, so
 * it still navigates on the root NavController like the note editor does.
 */
@Composable
fun DeckMessagesColumn(
    navController: NavController,
    navBackStackEntry: NavBackStackEntry,
    onConversationOpen: (String) -> Unit,
    onProfileOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val viewModel = hiltViewModel<MessageConversationListViewModel>(navBackStackEntry)
    MessageListScreen(
        viewModel = viewModel,
        callbacks = MessageConversationListContract.ScreenCallbacks(
            onConversationClick = onConversationOpen,
            onProfileClick = onProfileOpen,
            onNewMessageClick = { navController.navigateToNewMessage() },
            onClose = onClose,
        ),
    )
}
