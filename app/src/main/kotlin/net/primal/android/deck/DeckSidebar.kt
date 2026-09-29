package net.primal.android.deck

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.primal.domain.decks.Deck

private val SidebarWidth = 72.dp

/** Discord/Slack-style deck switcher rail. Long-press a deck for rename/move/delete. */
@Composable
fun DeckSidebar(
    decks: List<Deck>,
    activeDeckId: String?,
    onDeckClick: (String) -> Unit,
    onAddDeckClick: () -> Unit,
    onRenameDeck: (Deck) -> Unit,
    onMoveDeckUp: (Deck) -> Unit,
    onMoveDeckDown: (Deck) -> Unit,
    onDeleteDeck: (Deck) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .padding(vertical = 8.dp),
    ) {
        decks.forEachIndexed { index, deck ->
            DeckSidebarEntry(
                deck = deck,
                isActive = deck.id == activeDeckId,
                canMoveUp = index > 0,
                canMoveDown = index < decks.lastIndex,
                onClick = { onDeckClick(deck.id) },
                onRename = { onRenameDeck(deck) },
                onMoveUp = { onMoveDeckUp(deck) },
                onMoveDown = { onMoveDeckDown(deck) },
                onDelete = { onDeleteDeck(deck) },
            )
        }
        IconButton(onClick = onAddDeckClick) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckSidebarEntry(
    deck: Deck,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                )
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = deck.name.take(1).uppercase(),
                color = if (isActive) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("Rinomina") }, onClick = { showMenu = false; onRename() })
            DropdownMenuItem(
                text = { Text("Sposta su") },
                enabled = canMoveUp,
                onClick = { showMenu = false; onMoveUp() },
            )
            DropdownMenuItem(
                text = { Text("Sposta giù") },
                enabled = canMoveDown,
                onClick = { showMenu = false; onMoveDown() },
            )
            DropdownMenuItem(text = { Text("Elimina") }, onClick = { showMenu = false; onDelete() })
        }
    }
}
