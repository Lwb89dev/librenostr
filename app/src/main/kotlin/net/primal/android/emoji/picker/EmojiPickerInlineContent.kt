package net.primal.android.emoji.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.EmojiPack
import net.primal.android.emoji.ui.CustomEmojiImage
import net.primal.android.theme.AppTheme

/**
 * The composer's custom emoji panel, shown above the toolbar like the GIF panel: one tab per pack
 * (built-in first, then the packs on the account's emoji list), a grid of that pack's emoji, and a
 * way into Settings to add, make or remove packs. Tapping an emoji hands it to [onEmojiSelected],
 * which writes its `:shortcode:` into the note; the panel stays open so several can be added.
 */
@Composable
fun EmojiPickerInlineContent(
    viewModel: EmojiPickerViewModel,
    onDismiss: () -> Unit,
    onEmojiSelected: (CustomEmoji) -> Unit,
    onManagePacks: () -> Unit,
) {
    val packs by viewModel.packs.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onPickerShown() }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val selectedPack = packs.getOrNull(selectedIndex) ?: packs.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .shadow(18.dp, RoundedCornerShape(22.dp))
            .background(AppTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiPackTabs(
                modifier = Modifier.weight(1f),
                packs = packs,
                selectedPack = selectedPack,
                onPackClick = { selectedIndex = it },
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.emoji_picker_close),
                    tint = AppTheme.colorScheme.onSurface,
                )
            }
        }
        EmojiGrid(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 200.dp),
            emojis = selectedPack?.emojis.orEmpty(),
            onEmojiClick = onEmojiSelected,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = selectedPack?.displayTitle().orEmpty(),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onManagePacks) {
                Text(text = stringResource(id = R.string.emoji_picker_manage_packs))
            }
        }
    }
}

/** One tab per pack, each showing the pack's first emoji as its icon. */
@Composable
private fun EmojiPackTabs(
    packs: List<EmojiPack>,
    selectedPack: EmojiPack?,
    onPackClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(packs, key = { _, pack -> pack.address }) { index, pack ->
            val isSelected = pack.address == selectedPack?.address
            val icon = pack.emojis.firstOrNull() ?: return@itemsIndexed
            CustomEmojiImage(
                url = icon.url,
                shortcode = icon.shortcode,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, AppTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onPackClick(index) }
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun EmojiGrid(
    emojis: List<CustomEmoji>,
    onEmojiClick: (CustomEmoji) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (emojis.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }
    LazyVerticalGrid(
        modifier = modifier,
        columns = GridCells.Adaptive(minSize = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(emojis, key = { it.shortcode }) { emoji ->
            CustomEmojiImage(
                url = emoji.url,
                shortcode = emoji.shortcode,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Transparent)
                    .clickable { onEmojiClick(emoji) }
                    .padding(4.dp),
            )
        }
    }
}

/** A pack's title, or the label of the account's loose emoji (which have no pack and no title). */
@Composable
fun EmojiPack.displayTitle(): String = title.ifBlank { stringResource(id = R.string.emoji_picker_my_emoji) }
