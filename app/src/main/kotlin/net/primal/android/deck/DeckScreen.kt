package net.primal.android.deck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.runtime.DisposableLifecycleObserverEffect
import net.primal.android.deck.DeckContract.MoveDirection
import net.primal.android.deck.DeckContract.UiEvent
import net.primal.android.drawer.DrawerScreenDestination
import net.primal.android.main.MainContract
import net.primal.android.main.MainViewModel
import net.primal.android.navigation.navigateToHome
import net.primal.android.navigation.navigateToNoteEditor
import net.primal.android.navigation.noteCallbacksHandler
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.domain.decks.Deck
import net.primal.domain.decks.DeckColumn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Multi-column layout shown instead of [net.primal.android.main.MainScreen] when a tablet is
 * held in landscape (see [net.primal.android.core.compose.adaptive.rememberIsDeckModeEligible]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun DeckScreen(
    navController: NavController,
    navBackStackEntry: NavBackStackEntry,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
) {
    val viewModel = hiltViewModel<DeckViewModel>(navBackStackEntry)
    val state by viewModel.state.collectAsState()
    val noteCallbacks = noteCallbacksHandler(navController = navController)
    val activeDeck = state.activeDeck

    // Same MainViewModel instance MainScreen uses (shared ViewModelStoreOwner), so switching
    // between the two on rotation never double-fires these — periodic profile/follow/mute-list
    // refresh and the post-account-switch redirect need to happen here too, not just there.
    val mainViewModel = hiltViewModel<MainViewModel>(navBackStackEntry)
    DisposableLifecycleObserverEffect(mainViewModel) {
        if (it == Lifecycle.Event.ON_START) mainViewModel.setEvent(MainContract.UiEvent.RequestUserDataUpdate)
    }
    LaunchedEffect(mainViewModel, mainViewModel.effects) {
        mainViewModel.effects.collect {
            when (it) {
                MainContract.SideEffect.AccountSwitched -> navController.navigateToHome()
            }
        }
    }

    var showAddColumnSheet by remember { mutableStateOf(false) }
    var showCreateDeckDialog by remember { mutableStateOf(false) }
    var deckToRename by remember { mutableStateOf<Deck?>(null) }
    var deckToDelete by remember { mutableStateOf<Deck?>(null) }

    PrimalScaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = { navController.navigateToNoteEditor() }) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                    }
                    IconButton(onClick = { onDrawerDestinationClick(DrawerScreenDestination.Settings) }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            if (activeDeck != null) {
                FloatingActionButton(onClick = { showAddColumnSheet = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                }
            }
        },
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            DeckSidebar(
                decks = state.decks,
                activeDeckId = activeDeck?.id,
                onDeckClick = { deckId -> viewModel.setEvent(UiEvent.SelectDeck(deckId = deckId)) },
                onAddDeckClick = { showCreateDeckDialog = true },
                onRenameDeck = { deck -> deckToRename = deck },
                onMoveDeckUp = { deck ->
                    viewModel.setEvent(UiEvent.MoveDeck(deckId = deck.id, direction = MoveDirection.Up))
                },
                onMoveDeckDown = { deck ->
                    viewModel.setEvent(UiEvent.MoveDeck(deckId = deck.id, direction = MoveDirection.Down))
                },
                onDeleteDeck = { deck -> deckToDelete = deck },
            )

            if (activeDeck == null || activeDeck.columns.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Deck mode", style = MaterialTheme.typography.headlineSmall)
                }
            } else {
                DeckColumnsRow(
                    deck = activeDeck,
                    navController = navController,
                    navBackStackEntry = navBackStackEntry,
                    noteCallbacks = noteCallbacks,
                    onMoveColumn = { column, direction ->
                        viewModel.setEvent(
                            UiEvent.MoveColumn(deckId = activeDeck.id, columnId = column.id, direction = direction),
                        )
                    },
                    onReorderColumns = { orderedIds ->
                        viewModel.setEvent(
                            UiEvent.ReorderColumns(deckId = activeDeck.id, orderedColumnIds = orderedIds),
                        )
                    },
                    onRemoveColumn = { column ->
                        viewModel.setEvent(UiEvent.RemoveColumn(deckId = activeDeck.id, columnId = column.id))
                    },
                )
            }
        }
    }

    if (showAddColumnSheet && activeDeck != null) {
        AddDeckColumnBottomSheet(
            userNoteFeeds = state.userNoteFeeds,
            userReadsFeeds = state.userReadsFeeds,
            onDismissRequest = { showAddColumnSheet = false },
            onAddColumn = { type, params ->
                viewModel.setEvent(UiEvent.AddColumn(deckId = activeDeck.id, type = type, params = params))
            },
        )
    }

    if (showCreateDeckDialog) {
        DeckNameDialog(
            title = "Nuovo deck",
            onDismissRequest = { showCreateDeckDialog = false },
            onConfirm = { name ->
                viewModel.setEvent(UiEvent.CreateDeck(name = name))
                showCreateDeckDialog = false
            },
        )
    }

    deckToRename?.let { deck ->
        DeckNameDialog(
            title = "Rinomina deck",
            initialName = deck.name,
            onDismissRequest = { deckToRename = null },
            onConfirm = { name ->
                viewModel.setEvent(UiEvent.RenameDeck(deckId = deck.id, name = name))
                deckToRename = null
            },
        )
    }

    deckToDelete?.let { deck ->
        DeleteDeckConfirmationDialog(
            deckName = deck.name,
            onDismissRequest = { deckToDelete = null },
            onConfirm = {
                viewModel.setEvent(UiEvent.DeleteDeck(deckId = deck.id))
                deckToDelete = null
            },
        )
    }
}

/** Split out from [DeckScreen] mainly to keep the drag/reorder wiring at a sane indentation. */
@Composable
private fun DeckColumnsRow(
    deck: Deck,
    navController: NavController,
    navBackStackEntry: NavBackStackEntry,
    noteCallbacks: NoteCallbacks,
    onMoveColumn: (DeckColumn, MoveDirection) -> Unit,
    onReorderColumns: (List<String>) -> Unit,
    onRemoveColumn: (DeckColumn) -> Unit,
) {
    var localColumns by remember(deck.columns) { mutableStateOf(deck.columns) }
    val columnsListState = rememberLazyListState()
    val reorderableColumnsState = rememberReorderableLazyListState(columnsListState) { from, to ->
        // Visual-only during the drag: committing to the repository on every intermediate swap
        // races concurrent writes against each other and can leave the column jumping back under
        // the finger. The actual save happens once, in onColumnDragStopped below.
        localColumns = localColumns.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyRow(
        state = columnsListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        itemsIndexed(items = localColumns, key = { _, column -> column.id }) { index, column ->
            ReorderableItem(state = reorderableColumnsState, key = column.id) {
                DeckColumnView(
                    column = column,
                    navController = navController,
                    navBackStackEntry = navBackStackEntry,
                    noteCallbacks = noteCallbacks,
                    onMoveColumnUp = if (index > 0) ({ onMoveColumn(column, MoveDirection.Up) }) else null,
                    onMoveColumnDown = if (index < localColumns.lastIndex) {
                        { onMoveColumn(column, MoveDirection.Down) }
                    } else {
                        null
                    },
                    onRemoveColumn = { onRemoveColumn(column) },
                    onColumnDragStopped = { onReorderColumns(localColumns.map { it.id }) },
                )
            }
        }
    }
}
