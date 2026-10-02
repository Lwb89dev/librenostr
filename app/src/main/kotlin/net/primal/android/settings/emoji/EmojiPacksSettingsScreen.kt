package net.primal.android.settings.emoji

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalDefaults
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.SnackbarErrorHandler
import net.primal.android.core.compose.button.PrimalFilledButton
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.errors.resolveUiErrorMessage
import net.primal.android.emoji.model.EmojiPack
import net.primal.android.emoji.picker.displayTitle
import net.primal.android.emoji.ui.CustomEmojiImage
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.UiEvent
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.UiState
import net.primal.android.theme.AppTheme

@Composable
fun EmojiPacksSettingsScreen(
    viewModel: EmojiPacksSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    EmojiPacksSettingsScreen(
        state = state,
        eventPublisher = viewModel::setEvent,
        onClose = onClose,
        embedded = embedded,
    )
}

/**
 * Settings → Emoji packs: the NIP-30 packs the composer offers, and where they come from.
 *
 * Packs are Nostr events, so everything here is portable: a pack added here shows up in every
 * client that reads the account's emoji list (kind 10030), and a pack made here (kind 30030) can be
 * added by anyone. The built-in LibreNostr pack is the one exception — always there, nothing to add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPacksSettingsScreen(
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
    onClose: () -> Unit,
    embedded: Boolean,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    SnackbarErrorHandler(
        error = state.error,
        snackbarHostState = snackbarHostState,
        errorMessageResolver = { it.resolveUiErrorMessage(context) },
        onErrorDismiss = { eventPublisher(UiEvent.DismissError) },
    )
    val back = { if (state.editor != null) eventPublisher(UiEvent.CloseEditor) else onClose() }
    BackHandler(enabled = !embedded || state.editor != null) { back() }

    // Embedded in the two-pane settings layout the pane has its own header; the editor still gets
    // a bar, because it is the only way back to the list from there.
    val showTopBar = !embedded || state.editor != null
    PrimalScaffold(
        topBar = if (showTopBar) {
            {
                PrimalTopAppBar(
                    title = stringResource(id = R.string.settings_emoji_packs_title),
                    navigationIcon = PrimalIcons.ArrowBack,
                    navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                    onNavigationIconClick = back,
                )
            }
        } else {
            null
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        content = { paddingValues ->
            val editor = state.editor
            if (editor != null) {
                EmojiPackEditor(
                    modifier = Modifier.padding(paddingValues),
                    editor = editor,
                    isExistingPack = editor.identifier != null,
                    eventPublisher = eventPublisher,
                    onDelete = state.ownPacks.firstOrNull { it.identifier == editor.identifier }?.let { pack ->
                        { eventPublisher(UiEvent.DeletePack(pack)) }
                    },
                )
            } else {
                EmojiPacksList(
                    contentPadding = paddingValues,
                    state = state,
                    eventPublisher = eventPublisher,
                )
            }
        },
    )
}

@Composable
private fun EmojiPacksList(
    contentPadding: PaddingValues,
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colorScheme.surfaceVariant),
        contentPadding = contentPadding,
    ) {
        sectionHeader(R.string.emoji_packs_section_yours, loading = state.refreshing)
        items(state.enabledPacks, key = { "enabled:${it.address}" }) { pack ->
            EmojiPackRow(pack = pack) {
                EnabledPackActions(pack = pack, state = state, eventPublisher = eventPublisher)
            }
        }
        val ownNotEnabled = state.ownPacks.filterNot { it.address in state.enabledAddresses }
        items(ownNotEnabled, key = { "own:${it.address}" }) { pack ->
            EmojiPackRow(pack = pack) { AddPackButton(pack = pack, state = state, eventPublisher = eventPublisher) }
        }
        item(key = "create") { CreatePackButton(onClick = { eventPublisher(UiEvent.CreatePack) }) }
        item(key = "link") { AddFromLinkRow(state = state, eventPublisher = eventPublisher) }

        sectionHeader(R.string.emoji_packs_section_discover, loading = state.discovering)
        val discoverable = state.discoveredPacks.filterNot { it.address in state.enabledAddresses }
        if (discoverable.isEmpty() && !state.discovering) {
            item(key = "discoverEmpty") { HintText(text = stringResource(id = R.string.emoji_packs_discover_empty)) }
        }
        items(discoverable, key = { "discover:${it.address}" }) { pack ->
            EmojiPackRow(pack = pack) { AddPackButton(pack = pack, state = state, eventPublisher = eventPublisher) }
        }
    }
}

private fun LazyListScope.sectionHeader(titleRes: Int, loading: Boolean) {
    item(key = "header:$titleRes") {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(id = titleRes).uppercase(),
                style = AppTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
            )
            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** A pack: a strip of its first emoji, its title, how many it has, and [actions] on the right. */
@Composable
private fun EmojiPackRow(pack: EmojiPack, actions: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pack.displayTitle(),
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.emoji_packs_emoji_count, pack.emojis.size, pack.emojis.size),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                )
                EmojiStrip(pack = pack)
            }
            actions()
        }
        PrimalDivider()
    }
}

@Composable
private fun EmojiStrip(pack: EmojiPack) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pack.emojis.take(PREVIEW_EMOJI_COUNT).forEach { emoji ->
            CustomEmojiImage(url = emoji.url, shortcode = emoji.shortcode, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun EnabledPackActions(
    pack: EmojiPack,
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
) {
    if (pack.isBuiltIn) {
        HintText(text = stringResource(id = R.string.emoji_packs_built_in))
        return
    }
    val busy = pack.address in state.busyAddresses
    Column(horizontalAlignment = Alignment.End) {
        if (pack.ownerPubkey == state.userId && pack.identifier.isNotEmpty()) {
            TextButton(enabled = !busy, onClick = { eventPublisher(UiEvent.EditPack(pack)) }) {
                Text(text = stringResource(id = R.string.emoji_packs_edit))
            }
        }
        // The account's loose emoji have no pack address to take off the list.
        if (pack.identifier.isNotEmpty()) {
            TextButton(enabled = !busy, onClick = { eventPublisher(UiEvent.RemovePack(pack.address)) }) {
                Text(text = stringResource(id = R.string.emoji_packs_remove))
            }
        }
    }
}

@Composable
private fun AddPackButton(
    pack: EmojiPack,
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
) {
    TextButton(
        enabled = pack.address !in state.busyAddresses,
        onClick = { eventPublisher(UiEvent.AddPack(pack)) },
    ) {
        Text(text = stringResource(id = R.string.emoji_packs_add))
    }
}

@Composable
private fun CreatePackButton(onClick: () -> Unit) {
    PrimalFilledButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        height = 48.dp,
        onClick = onClick,
    ) {
        Text(text = stringResource(id = R.string.emoji_packs_create))
    }
}

@Composable
private fun AddFromLinkRow(state: UiState, eventPublisher: (UiEvent) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = state.linkInput,
            onValueChange = { eventPublisher(UiEvent.UpdateLinkInput(it)) },
            placeholder = { Text(text = stringResource(id = R.string.emoji_packs_link_hint)) },
            singleLine = true,
            colors = PrimalDefaults.outlinedTextFieldColors(),
            shape = AppTheme.shapes.medium,
        )
        Box(modifier = Modifier.padding(start = 8.dp)) {
            if (state.addingByLink) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    enabled = state.linkInput.isNotBlank(),
                    onClick = { eventPublisher(UiEvent.AddPackFromLink) },
                ) {
                    Text(text = stringResource(id = R.string.emoji_packs_add))
                }
            }
        }
    }
}

@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        text = text,
        style = AppTheme.typography.bodySmall,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
    )
}

private const val PREVIEW_EMOJI_COUNT = 8
