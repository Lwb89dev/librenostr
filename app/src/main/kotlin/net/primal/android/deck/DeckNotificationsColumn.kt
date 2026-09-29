package net.primal.android.deck

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import net.primal.android.main.notifications.NotificationsContent
import net.primal.android.main.notifications.NotificationsContract.UiEvent
import net.primal.android.main.notifications.NotificationsViewModel
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks

/**
 * Reuses the same [NotificationsViewModel]/[NotificationsContent] MainScreen uses for its
 * Alerts tab, scoped to the shared `navBackStackEntry` so it's the very same instance (and
 * badge/seen state) whichever of MainScreen/DeckScreen is currently showing for that entry.
 */
@Composable
fun DeckNotificationsColumn(navBackStackEntry: NavBackStackEntry, noteCallbacks: NoteCallbacks) {
    val viewModel = hiltViewModel<NotificationsViewModel>(navBackStackEntry)
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 1 })
    val shouldAnimateScrollToTop = remember { mutableStateOf(false) }

    NotificationsContent(
        pagerState = pagerState,
        badges = state.badges,
        seenNotificationsProvider = viewModel::seenNotificationsForGroup,
        unseenNotificationsProvider = viewModel::unseenNotificationsForGroup,
        onNotificationsSeen = { group -> viewModel.setEvent(UiEvent.NotificationsSeen(group = group)) },
        paddingValues = PaddingValues(0.dp),
        noteCallbacks = noteCallbacks,
        shouldAnimateScrollToTop = shouldAnimateScrollToTop,
    )
}
