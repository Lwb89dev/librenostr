package net.primal.android.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import net.primal.android.core.compose.PrimalDefaults
import net.primal.android.explore.search.SearchContract
import net.primal.android.explore.search.SearchViewModel
import net.primal.android.explore.search.ui.UserProfileListItem
import net.primal.domain.decks.DeckColumnParams
import net.primal.domain.decks.DeckColumnType
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.nostr.cryptography.utils.bech32ToHexOrNull
import net.primal.domain.nostr.utils.isValidHex

private val SELECTABLE_TYPES = listOf(
    DeckColumnType.Home to "Home",
    DeckColumnType.Hashtag to "Hashtag",
    DeckColumnType.Profile to "Profilo",
    DeckColumnType.Reads to "Reads",
    DeckColumnType.Notifications to "Notifiche",
    DeckColumnType.Messages to "Messaggi",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeckColumnBottomSheet(
    userNoteFeeds: List<PrimalFeed>,
    userReadsFeeds: List<PrimalFeed>,
    onDismissRequest: () -> Unit,
    onAddColumn: (type: DeckColumnType, params: DeckColumnParams) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var selectedType by remember { mutableStateOf(DeckColumnType.Home) }

    fun addAndDismiss(type: DeckColumnType, params: DeckColumnParams) {
        onAddColumn(type, params)
        onDismissRequest()
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(text = "Aggiungi colonna", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Row {
                SELECTABLE_TYPES.forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedType) {
                DeckColumnType.Home ->
                    FeedPickerContent(
                        feeds = userNoteFeeds,
                        onFeedSelected = { feed ->
                            addAndDismiss(DeckColumnType.Home, DeckColumnParams(feedSpec = feed.spec))
                        },
                    )

                DeckColumnType.Reads ->
                    FeedPickerContent(
                        feeds = userReadsFeeds,
                        onFeedSelected = { feed ->
                            addAndDismiss(DeckColumnType.Reads, DeckColumnParams(feedSpec = feed.spec))
                        },
                    )

                DeckColumnType.Hashtag ->
                    HashtagInputContent(
                        onConfirm = { hashtag ->
                            addAndDismiss(DeckColumnType.Hashtag, DeckColumnParams(hashtag = hashtag))
                        },
                    )

                DeckColumnType.Profile ->
                    ProfilePickerContent(
                        onProfileSelected = { pubkeyHex ->
                            addAndDismiss(DeckColumnType.Profile, DeckColumnParams(pubkey = pubkeyHex))
                        },
                    )

                DeckColumnType.Notifications, DeckColumnType.Messages ->
                    Button(onClick = { addAndDismiss(selectedType, DeckColumnParams()) }) {
                        Text("Aggiungi")
                    }

                else -> Unit
            }
        }
    }
}

@Composable
private fun FeedPickerContent(feeds: List<PrimalFeed>, onFeedSelected: (PrimalFeed) -> Unit) {
    if (feeds.isEmpty()) {
        Text(text = "Nessun feed salvato", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        feeds.forEach { feed ->
            Text(
                text = feed.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFeedSelected(feed) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun HashtagInputContent(onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.removePrefix("#") },
            label = { Text("Hashtag") },
            modifier = Modifier.fillMaxWidth(),
            colors = PrimalDefaults.outlinedTextFieldColors(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
            Text("Aggiungi")
        }
    }
}

/**
 * Search by name/NIP-05 (via the same [SearchViewModel] the Explore search screen uses) with a
 * direct-paste fast path for when the user already has an npub or hex pubkey at hand.
 */
@Composable
private fun ProfilePickerContent(onProfileSelected: (String) -> Unit) {
    val viewModel = hiltViewModel<SearchViewModel>()
    val state by viewModel.state.collectAsState()

    val directPubkey = state.searchQuery.trim().let { input ->
        when {
            input.isEmpty() -> null
            input.startsWith("npub") -> input.bech32ToHexOrNull()
            input.isValidHex() -> input
            else -> null
        }
    }

    Column {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.setEvent(SearchContract.UiEvent.SearchQueryUpdated(query = it)) },
            label = { Text("Nome, NIP-05, npub o hex pubkey") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = PrimalDefaults.outlinedTextFieldColors(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (directPubkey != null) {
            Button(onClick = { onProfileSelected(directPubkey) }, modifier = Modifier.fillMaxWidth()) {
                Text("Usa questo pubkey")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        val results = state.searchResults.ifEmpty { state.recommendedUsers }
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            items(items = results, key = { it.profileId }) { item ->
                UserProfileListItem(data = item, onClick = { user -> onProfileSelected(user.profileId) })
            }
        }
    }
}
