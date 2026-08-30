package net.primal.android.feeds.list.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.FeedPicker
import net.primal.android.core.compose.icons.primaliconpack.FeedReplies
import net.primal.android.core.compose.picker.BasePickerListItem
import net.primal.android.core.compose.picker.PickerListItemCheckIcon
import net.primal.android.feeds.list.ui.model.FeedUi

@Composable
fun FeedListItem(
    modifier: Modifier,
    data: FeedUi,
    selected: Boolean,
    isEditMode: Boolean = false,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    editOptions: @Composable () -> Unit,
) {
    val effectiveSelected = selected && !isEditMode
    val leadingIcon = when {
        data.spec.contains("\"include_replies\":true") -> PrimalIcons.FeedReplies
        data.spec.contains("\"id\":\"latest\"") -> PrimalIcons.FeedPicker
        else -> null
    }
    BasePickerListItem(
        modifier = modifier,
        title = data.title,
        subtitle = data.description.ifEmpty { null },
        selected = effectiveSelected,
        containerColor = containerColor,
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    modifier = Modifier.size(22.dp),
                    imageVector = icon,
                    contentDescription = null,
                    tint = net.primal.android.theme.AppTheme.colorScheme.primary,
                )
            }
        },
        titleAlignment = if (data.description.isNotEmpty()) TextAlign.Start else TextAlign.Center,
        trailingContent = {
            if (isEditMode) {
                editOptions()
            } else if (selected) {
                PickerListItemCheckIcon()
            }
        },
    )
}
