package net.primal.android.notes.feed.note.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import net.primal.android.R
import net.primal.android.core.compose.IconText
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.bubble.anchor
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.FeedBookmarkFilled
import net.primal.android.core.compose.icons.primaliconpack.FeedBookmarkOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedLikeOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedNewLikeFilled
import net.primal.android.core.compose.icons.primaliconpack.FeedReplyOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedNewReplyFilled
import net.primal.android.core.compose.icons.primaliconpack.FeedRepostsOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedNewRepostsFilled
import net.primal.android.core.compose.icons.primaliconpack.FeedZapOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedNewZapFilled
import net.primal.android.notes.feed.model.EventStatsUi
import net.primal.android.notes.feed.model.FeedPostAction
import net.primal.android.theme.AppTheme

@Composable
fun FeedNoteActionsRow(
    modifier: Modifier,
    eventStats: EventStatsUi,
    highlightedNote: Boolean = false,
    showCounts: Boolean = true,
    /** Adds a bookmark toggle as the last action. It carries no count, so it costs the row only
     * the icon's own width; callers that cannot bookmark the event (a private message, say) leave
     * it off rather than show a button that does nothing. */
    showBookmark: Boolean = false,
    isBookmarked: Boolean = false,
    onPostAction: ((FeedPostAction) -> Unit)? = null,
    onPostLongPressAction: ((FeedPostAction) -> Unit)? = null,
    repostAnchor: AnchorHandle? = null,
    /** Icon/text color for a not-yet-highlighted stat. Defaults to the app's own muted theme
     * color, which reads fine on a normal surface but is too low-contrast over an arbitrary
     * photo or video background — callers hosting this row on top of media should pass an
     * explicit high-contrast color (e.g. white) instead. */
    unhighlightedColor: Color = AppTheme.extraColorScheme.onSurfaceVariantAlt4,
    /** Overrides the normal highlightedNote-derived 17sp/26sp sizing outright, for a context
     * that needs its own specific touch-target size (e.g. the media gallery's overlay bar). */
    iconSizeOverride: TextUnit? = null,
) {
    val iconSize = iconSizeOverride ?: if (highlightedNote) 26.sp else 17.sp
    val numberFormat = remember { NumberFormat.getNumberInstance() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SingleEventStat(
            textCount = if (showCounts) eventStats.repliesCount.toPostStatString(numberFormat) else "",
            highlighted = eventStats.userReplied,
            iconSize = iconSize,
            iconVector = PrimalIcons.FeedReplyOutline,
            iconVectorHighlight = PrimalIcons.FeedNewReplyFilled,
            colorHighlight = AppTheme.extraColorScheme.replied,
            unhighlightedColor = unhighlightedColor,
            onClick = onPostAction?.let {
                { onPostAction(FeedPostAction.Reply) }
            },
            onLongClick = onPostLongPressAction?.let {
                { onPostLongPressAction(FeedPostAction.Reply) }
            },
            iconContentDescription = stringResource(id = R.string.accessibility_replies_count),
        )

        SingleEventStat(
            textCount = if (showCounts) eventStats.satsZapped.toPostStatString(numberFormat) else "",
            highlighted = eventStats.userZapped,
            iconVector = PrimalIcons.FeedZapOutline,
            // Zaps icons are tiny when below 18.sp so we have to increase it by 20% to fit in
            iconSize = if (!highlightedNote) iconSize.times(other = 1.2f) else iconSize,
            iconVectorHighlight = PrimalIcons.FeedNewZapFilled,
            colorHighlight = AppTheme.extraColorScheme.zapped,
            unhighlightedColor = unhighlightedColor,
            onClick = if (onPostAction != null) {
                { onPostAction(FeedPostAction.Zap) }
            } else {
                {}
            },
            onLongClick = onPostLongPressAction?.let {
                { onPostLongPressAction(FeedPostAction.Zap) }
            },
            iconContentDescription = stringResource(id = R.string.accessibility_zaps_count),
        )

        SingleEventStat(
            textCount = if (showCounts) eventStats.likesCount.toPostStatString(numberFormat) else "",
            highlighted = eventStats.userLiked,
            iconSize = iconSize,
            iconVector = PrimalIcons.FeedLikeOutline,
            iconVectorHighlight = PrimalIcons.FeedNewLikeFilled,
            colorHighlight = AppTheme.extraColorScheme.liked,
            unhighlightedColor = unhighlightedColor,
            onClick = if (!eventStats.userLiked && onPostAction != null) {
                { onPostAction(FeedPostAction.Like) }
            } else {
                null
            },
            onLongClick = onPostLongPressAction?.let {
                { onPostLongPressAction(FeedPostAction.Like) }
            },
            iconContentDescription = stringResource(id = R.string.accessibility_likes_count),
        )

        SingleEventStat(
            modifier = repostAnchor?.let { Modifier.anchor(it) } ?: Modifier,
            textCount = if (showCounts) eventStats.repostsCount.toPostStatString(numberFormat) else "",
            highlighted = eventStats.userReposted,
            iconSize = iconSize,
            iconVector = PrimalIcons.FeedRepostsOutline,
            iconVectorHighlight = PrimalIcons.FeedNewRepostsFilled,
            colorHighlight = AppTheme.extraColorScheme.reposted,
            unhighlightedColor = unhighlightedColor,
            onClick = onPostAction?.let {
                { onPostAction(FeedPostAction.Repost) }
            },
            onLongClick = onPostLongPressAction?.let {
                { onPostLongPressAction(FeedPostAction.Repost) }
            },
            iconContentDescription = stringResource(id = R.string.accessibility_repost_count),
        )

        if (showBookmark) {
            SingleEventStat(
                textCount = "",
                highlighted = isBookmarked,
                iconSize = iconSize,
                iconVector = PrimalIcons.FeedBookmarkOutline,
                iconVectorHighlight = PrimalIcons.FeedBookmarkFilled,
                colorHighlight = AppTheme.extraColorScheme.bookmarked,
                unhighlightedColor = unhighlightedColor,
                onClick = onPostAction?.let {
                    { onPostAction(FeedPostAction.Bookmark) }
                },
                onLongClick = onPostLongPressAction?.let {
                    { onPostLongPressAction(FeedPostAction.Bookmark) }
                },
                iconContentDescription = stringResource(id = R.string.accessibility_bookmark),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SingleEventStat(
    modifier: Modifier = Modifier,
    textCount: String,
    highlighted: Boolean,
    iconSize: TextUnit,
    iconVector: ImageVector,
    iconVectorHighlight: ImageVector,
    colorHighlight: Color,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    iconContentDescription: String? = null,
    textStyle: TextStyle = AppTheme.typography.bodySmall,
    unhighlightedColor: Color = AppTheme.extraColorScheme.onSurfaceVariantAlt4,
) {
    IconText(
        modifier = modifier
            .animateContentSize()
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            ),
        leadingIcon = if (!highlighted) iconVector else iconVectorHighlight,
        leadingIconContentDescription = iconContentDescription,
        leadingIconTintColor = if (!highlighted) {
            unhighlightedColor
        } else {
            null
        },
        iconSize = iconSize,
        text = textCount,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = textStyle,
        color = if (!highlighted) unhighlightedColor else colorHighlight,
    )
}

private fun Long.toPostStatString(numberFormat: NumberFormat): String {
    return if (this > 0) {
        numberFormat.format(this)
    } else {
        ""
    }
}
