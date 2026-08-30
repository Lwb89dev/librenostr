package net.primal.android.notes.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import net.primal.android.R
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.DeleteRepost
import net.primal.android.core.compose.icons.primaliconpack.Quote
import net.primal.android.core.compose.icons.primaliconpack.Repost
import net.primal.android.theme.AppTheme

@Composable
fun NoteRepostOrQuotePopup(
    anchor: AnchorHandle,
    isReposted: Boolean,
    onDismiss: () -> Unit,
    onRepostClick: () -> Unit,
    onDeleteRepostClick: () -> Unit,
    onPostQuoteClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
    ) {
        Popup(
            popupPositionProvider = RepostPopupPositionProvider(anchor),
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                modifier = Modifier.padding(8.dp),
                shape = RoundedCornerShape(18.dp),
                color = AppTheme.extraColorScheme.surfaceVariantAlt1,
                shadowElevation = 12.dp,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
            if (isReposted) {
                ActionButton(
                    text = stringResource(id = R.string.post_repost_again_button_confirmation),
                    leadingIcon = PrimalIcons.Repost,
                    onClick = {
                        onDismiss()
                        onRepostClick()
                    },
                )
                ActionButton(
                    text = stringResource(id = R.string.post_delete_repost_button_confirmation),
                    contentColor = AppTheme.colorScheme.error,
                    leadingIcon = PrimalIcons.DeleteRepost,
                    onClick = {
                        onDismiss()
                        onDeleteRepostClick()
                    },
                )
            } else {
                ActionButton(
                    text = stringResource(id = R.string.post_repost_button_confirmation),
                    leadingIcon = PrimalIcons.Repost,
                    onClick = {
                        onDismiss()
                        onRepostClick()
                    },
                )
            }

            ActionButton(
                text = stringResource(id = R.string.post_quote_button_confirmation),
                leadingIcon = PrimalIcons.Quote,
                onClick = {
                    onDismiss()
                    onPostQuoteClick()
                },
            )
                }
            }
        }
    }
}

/** Compatibility entry point for contexts without a note-action anchor (article details, notifications). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteRepostOrQuoteBottomSheet(
    isReposted: Boolean,
    onDismiss: () -> Unit,
    onRepostClick: () -> Unit,
    onDeleteRepostClick: () -> Unit,
    onPostQuoteClick: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = AppTheme.extraColorScheme.surfaceVariantAlt2,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isReposted) {
                ActionButton(
                    text = stringResource(R.string.post_repost_again_button_confirmation),
                    leadingIcon = PrimalIcons.Repost,
                    onClick = { onDismiss(); onRepostClick() },
                )
                ActionButton(
                    text = stringResource(R.string.post_delete_repost_button_confirmation),
                    contentColor = AppTheme.colorScheme.error,
                    leadingIcon = PrimalIcons.DeleteRepost,
                    onClick = { onDismiss(); onDeleteRepostClick() },
                )
            } else {
                ActionButton(
                    text = stringResource(R.string.post_repost_button_confirmation),
                    leadingIcon = PrimalIcons.Repost,
                    onClick = { onDismiss(); onRepostClick() },
                )
            }
            ActionButton(
                text = stringResource(R.string.post_quote_button_confirmation),
                leadingIcon = PrimalIcons.Quote,
                onClick = { onDismiss(); onPostQuoteClick() },
            )
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    text: String,
    leadingIcon: ImageVector,
    contentColor: Color = AppTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(22.dp),
            imageVector = leadingIcon,
            contentDescription = null,
            tint = contentColor,
        )
        androidx.compose.material3.Text(
            text = text,
            color = contentColor,
            style = AppTheme.typography.bodyLarge,
        )
    }
}

private class RepostPopupPositionProvider(
    private val anchor: AnchorHandle,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val target = anchor.rectInRoot ?: return IntOffset.Zero
        val margin = 8
        val left = (target.center.x - popupContentSize.width / 2f)
            .toInt()
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val above = (target.top - popupContentSize.height - margin).toInt()
        val below = (target.bottom + margin).toInt()
        val top = if (above >= margin) above else below.coerceAtMost(windowSize.height - popupContentSize.height - margin)
        return IntOffset(left, top)
    }
}
