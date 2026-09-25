package net.primal.android.notes.feed.note.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import net.primal.android.core.feedback.LikeChimePlayer
import net.primal.android.core.feedback.performConfirmHaptic
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
    val likeFeedback = rememberLikeFeedback()

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
            celebrateOnClick = true,
            onActivated = likeFeedback,
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
    celebrateOnClick: Boolean = false,
    onActivated: (() -> Unit)? = null,
) {
    val motionState = rememberEventStatMotionState(celebrate = celebrateOnClick)

    IconText(
        modifier = modifier
            .eventStatMotion(motionState)
            .animateContentSize()
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = {
                    if (onClick != null) {
                        motionState.start()
                        onActivated?.invoke()
                        onClick()
                    }
                },
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

@Suppress("MagicNumber")
@Composable
private fun rememberEventStatMotionState(celebrate: Boolean): EventStatMotionState {
    val state = remember { EventStatMotionState() }
    LaunchedEffect(state.sequence, celebrate) {
        if (state.sequence == 0) return@LaunchedEffect

        if (celebrate) {
            coroutineScope {
                launch {
                    state.scale.animateTo(
                        targetValue = 1f,
                        animationSpec = keyframes {
                            durationMillis = LIKE_ANIMATION_DURATION_MILLIS
                            1f at 0
                            0.78f at 55
                            1.38f at 175 using FastOutSlowInEasing
                            0.96f at 310
                            1f at LIKE_ANIMATION_DURATION_MILLIS
                        },
                    )
                }
                launch {
                    state.rotation.animateTo(
                        targetValue = 0f,
                        animationSpec = keyframes {
                            durationMillis = LIKE_ANIMATION_DURATION_MILLIS
                            0f at 0
                            -12f at 70
                            10f at 185
                            0f at 340
                        },
                    )
                }
            }
        } else {
            state.scale.snapTo(0.88f)
            state.scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh,
                ),
            )
        }
    }
    return state
}

private fun Modifier.eventStatMotion(state: EventStatMotionState): Modifier =
    graphicsLayer {
        scaleX = state.scale.value
        scaleY = state.scale.value
        rotationZ = state.rotation.value
    }

private class EventStatMotionState {
    val scale = Animatable(1f)
    val rotation = Animatable(0f)
    var sequence by mutableIntStateOf(0)
        private set

    fun start() {
        sequence++
    }
}

@Composable
private fun rememberLikeFeedback(): () -> Unit {
    val applicationContext = LocalContext.current.applicationContext
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    return remember(applicationContext, view, scope) {
        {
            view.performConfirmHaptic()
            scope.launch { LikeChimePlayer.play(applicationContext) }
        }
    }
}

private const val LIKE_ANIMATION_DURATION_MILLIS = 420

private fun Long.toPostStatString(numberFormat: NumberFormat): String {
    return if (this > 0) {
        numberFormat.format(this)
    } else {
        ""
    }
}
