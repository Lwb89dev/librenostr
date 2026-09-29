package net.primal.android.deck

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import net.primal.android.articles.feed.ArticleFeedList
import net.primal.android.notes.feed.list.NoteFeedList
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.domain.decks.DeckColumn
import net.primal.domain.decks.DeckColumnParams
import net.primal.domain.decks.DeckColumnType
import net.primal.domain.decks.resolveFeedSpec
import sh.calvin.reorderable.ReorderableCollectionItemScope

private val DeckColumnWidth = 380.dp

/**
 * Drilling into a note/profile/DM from inside a column flips [DeckColumnDestination] instead of
 * navigating on the root NavController — see that type's own doc for why. Must be called from
 * within a `ReorderableItem` (see [DeckScreen]) so the header's drag handle can grab the column.
 */
@Composable
fun ReorderableCollectionItemScope.DeckColumnView(
    column: DeckColumn,
    navController: NavController,
    navBackStackEntry: NavBackStackEntry,
    noteCallbacks: NoteCallbacks,
    onMoveColumnUp: (() -> Unit)?,
    onMoveColumnDown: (() -> Unit)?,
    onRemoveColumn: () -> Unit,
    onColumnDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable(column.id, stateSaver = DeckColumnDestinationSaver) {
        mutableStateOf<DeckColumnDestination>(DeckColumnDestination.Feed)
    }
    val goToFeed = { destination = DeckColumnDestination.Feed }

    val localNoteCallbacks = remember(noteCallbacks) {
        noteCallbacks.copy(
            onNoteClick = { noteId -> destination = DeckColumnDestination.Thread(noteId) },
            onProfileClick = { profileId -> destination = DeckColumnDestination.Profile(profileId) },
        )
    }

    // Every feed/thread/profile/chat ViewModel opened inside this column (NoteFeedList,
    // DeckThreadContent, DeckProfileContent, DeckChatContent, ...) resolves its ViewModelStore
    // through this local instead of the shared "main" route one — otherwise none of them are
    // ever released, since a keyed hiltViewModel() only stops being *requested* when you navigate
    // away, it does not get removed from a ViewModelStore that outlives the whole app session.
    // Clearing on dispose means opening N different threads over a session costs O(1) live
    // ViewModels per column, not O(N).
    //
    // A bare ViewModelStoreOwner is not enough: hiltViewModel() builds its Hilt-aware factory
    // from HasDefaultViewModelProviderFactory on the owner, and without it silently falls back to
    // ViewModelProvider.NewInstanceFactory, which tries every ViewModel's no-arg constructor —
    // fatal for one built by @AssistedInject, such as NoteFeedViewModel. The store stays local to
    // the column; only the factory (and the extras it needs to build with) is borrowed from the
    // real owner outside the deck.
    val realViewModelStoreOwner = LocalViewModelStoreOwner.current as HasDefaultViewModelProviderFactory
    val columnViewModelStoreOwner = remember(realViewModelStoreOwner) {
        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory
                get() = realViewModelStoreOwner.defaultViewModelProviderFactory
            override val defaultViewModelCreationExtras: CreationExtras
                get() = realViewModelStoreOwner.defaultViewModelCreationExtras
        }
    }
    DisposableEffect(columnViewModelStoreOwner) {
        onDispose { columnViewModelStoreOwner.viewModelStore.clear() }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides columnViewModelStoreOwner) {
    Column(modifier = modifier.width(DeckColumnWidth).fillMaxHeight()) {
        when (val dest = destination) {
            is DeckColumnDestination.Thread ->
                DeckThreadContent(
                    navController = navController,
                    noteId = dest.noteId,
                    noteCallbacks = localNoteCallbacks,
                    onBack = goToFeed,
                )

            is DeckColumnDestination.Profile ->
                DeckProfileContent(profileId = dest.profileId, noteCallbacks = localNoteCallbacks, onBack = goToFeed)

            is DeckColumnDestination.Chat ->
                DeckChatContent(participantId = dest.profileId, noteCallbacks = localNoteCallbacks, onBack = goToFeed)

            DeckColumnDestination.Feed ->
                if (column.type == DeckColumnType.Messages) {
                    DeckMessagesColumn(
                        navController = navController,
                        navBackStackEntry = navBackStackEntry,
                        onConversationOpen = { profileId -> destination = DeckColumnDestination.Chat(profileId) },
                        onProfileOpen = { profileId -> destination = DeckColumnDestination.Profile(profileId) },
                        onClose = onRemoveColumn,
                    )
                } else {
                    DeckColumnHeader(
                        column = column,
                        onMoveUp = onMoveColumnUp,
                        onMoveDown = onMoveColumnDown,
                        onRemoveColumn = onRemoveColumn,
                        onDragStopped = onColumnDragStopped,
                    )
                    DeckColumnContent(
                        column = column,
                        navBackStackEntry = navBackStackEntry,
                        noteCallbacks = localNoteCallbacks,
                    )
                }
        }
    }
    }
}

@Composable
private fun ReorderableCollectionItemScope.DeckColumnHeader(
    column: DeckColumn,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemoveColumn: () -> Unit,
    onDragStopped: () -> Unit,
) {
    val dragInteractionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            modifier = Modifier
                .draggableHandle(interactionSource = dragInteractionSource, onDragStopped = { onDragStopped() })
                .clearAndSetSemantics {},
            onClick = {},
            interactionSource = dragInteractionSource,
        ) {
            Icon(imageVector = Icons.Rounded.Menu, contentDescription = null)
        }
        Text(
            text = column.displayLabel(),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
            Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
            Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        IconButton(onClick = onRemoveColumn) {
            Icon(imageVector = Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun DeckColumnContent(column: DeckColumn, navBackStackEntry: NavBackStackEntry, noteCallbacks: NoteCallbacks) {
    when (column.type) {
        DeckColumnType.Home, DeckColumnType.Hashtag, DeckColumnType.Profile -> {
            val feedSpec = column.resolveFeedSpec()
            if (feedSpec != null) {
                NoteFeedList(feedSpec = feedSpec, noteCallbacks = noteCallbacks)
            } else {
                DeckColumnPlaceholder()
            }
        }

        DeckColumnType.Reads -> {
            val feedSpec = column.resolveFeedSpec()
            if (feedSpec != null) {
                ArticleFeedList(
                    feedSpec = feedSpec,
                    onArticleClick = { naddr -> noteCallbacks.onArticleClick?.invoke(naddr) },
                    onGetPremiumClick = {},
                )
            } else {
                DeckColumnPlaceholder()
            }
        }

        DeckColumnType.Notifications ->
            DeckNotificationsColumn(navBackStackEntry = navBackStackEntry, noteCallbacks = noteCallbacks)

        // Messages never actually reaches here — DeckColumnView returns early for it, since
        // DeckMessagesColumn brings its own top bar instead of the generic DeckColumnHeader.
        DeckColumnType.Explore, DeckColumnType.Messages -> {
            DeckColumnPlaceholder()
        }
    }
}

@Composable
private fun DeckColumnPlaceholder() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text(text = "Presto disponibile", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Enough of a hex pubkey to tell two profile columns apart in a narrow header. */
private const val PUBKEY_LABEL_CHARS = 10

/** So two columns of the same type (e.g. two Hashtag columns) are distinguishable at a glance. */
private fun DeckColumn.displayLabel(): String {
    val params = paramsJson.decodeFromJsonStringOrNull<DeckColumnParams>()
    return when (type) {
        DeckColumnType.Hashtag -> params?.hashtag?.let { "#$it" } ?: type.name
        DeckColumnType.Profile -> params?.pubkey?.let { it.take(PUBKEY_LABEL_CHARS) + "…" } ?: type.name
        else -> type.name
    }
}
