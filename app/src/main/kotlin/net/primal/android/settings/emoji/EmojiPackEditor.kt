package net.primal.android.settings.emoji

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalDefaults
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.button.PrimalFilledButton
import net.primal.android.emoji.Nip30
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.ui.CustomEmojiImage
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.PackEditorState
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.UiEvent
import net.primal.android.theme.AppTheme

/**
 * Making or editing one of the account's own packs: a name, then emoji added one image at a time.
 * Each image is uploaded to the account's Blossom servers as soon as it is picked (the emoji's URL
 * has to be public for anyone else to see it); the pack itself is only published on save.
 */
@Composable
internal fun EmojiPackEditor(
    editor: PackEditorState,
    isExistingPack: Boolean,
    eventPublisher: (UiEvent) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { eventPublisher(UiEvent.AddEmojiImage(it)) }
    }
    val shortcodeTaken = editor.emojis.any { it.shortcode == editor.pendingShortcode }
    val shortcodeInvalid = editor.pendingShortcode.isNotEmpty() &&
        (!Nip30.isValidShortcode(editor.pendingShortcode) || shortcodeTaken)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colorScheme.surfaceVariant),
    ) {
        item(key = "title") {
            EditorTextField(
                value = editor.title,
                onValueChange = { eventPublisher(UiEvent.UpdateEditorTitle(it)) },
                label = stringResource(id = R.string.emoji_pack_editor_name_hint),
            )
        }
        item(key = "add") {
            AddEmojiRow(
                editor = editor,
                shortcodeInvalid = shortcodeInvalid,
                eventPublisher = eventPublisher,
                onPickImage = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
        if (editor.emojis.isEmpty()) {
            item(key = "empty") { HintText(text = stringResource(id = R.string.emoji_pack_editor_empty)) }
        }
        items(editor.emojis, key = { it.shortcode }) { emoji ->
            EditorEmojiRow(emoji = emoji, onRemove = { eventPublisher(UiEvent.RemoveEditorEmoji(emoji.shortcode)) })
        }
        item(key = "save") {
            EditorActions(
                editor = editor,
                isExistingPack = isExistingPack,
                onSave = { eventPublisher(UiEvent.SaveEditor) },
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(text = it) } },
        colors = PrimalDefaults.outlinedTextFieldColors(),
        shape = AppTheme.shapes.medium,
    )
}

/** The shortcode for the next emoji, and the button that picks its image. */
@Composable
private fun AddEmojiRow(
    editor: PackEditorState,
    shortcodeInvalid: Boolean,
    eventPublisher: (UiEvent) -> Unit,
    onPickImage: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EditorTextField(
            modifier = Modifier.weight(1f),
            value = editor.pendingShortcode,
            onValueChange = { eventPublisher(UiEvent.UpdatePendingShortcode(it)) },
            label = stringResource(id = R.string.emoji_pack_editor_shortcode_hint),
            isError = shortcodeInvalid,
            supportingText = stringResource(id = R.string.emoji_pack_editor_shortcode_invalid)
                .takeIf { shortcodeInvalid },
        )
        if (editor.uploading) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 24.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            TextButton(
                modifier = Modifier.padding(end = 8.dp),
                enabled = !shortcodeInvalid && !editor.saving,
                onClick = onPickImage,
            ) {
                Text(text = stringResource(id = R.string.emoji_pack_editor_add_image))
            }
        }
    }
}

@Composable
private fun EditorEmojiRow(emoji: CustomEmoji, onRemove: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CustomEmojiImage(url = emoji.url, shortcode = emoji.shortcode, modifier = Modifier.size(36.dp))
            Text(
                modifier = Modifier.weight(1f),
                text = ":${emoji.shortcode}:",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.emoji_packs_remove),
                    tint = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
                )
            }
        }
        PrimalDivider()
    }
}

@Composable
private fun EditorActions(
    editor: PackEditorState,
    isExistingPack: Boolean,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        PrimalFilledButton(
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
            enabled = editor.canSave,
            onClick = onSave,
        ) {
            if (editor.saving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = stringResource(id = R.string.emoji_pack_editor_save))
            }
        }
        if (isExistingPack && onDelete != null) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !editor.saving,
                onClick = onDelete,
            ) {
                Text(text = stringResource(id = R.string.emoji_pack_editor_delete), color = AppTheme.colorScheme.error)
            }
        }
    }
}
