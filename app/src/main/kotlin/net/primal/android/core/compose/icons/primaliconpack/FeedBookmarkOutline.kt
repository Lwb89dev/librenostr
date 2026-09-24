package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons

/**
 * Bookmark ribbon drawn as a stroke, in the same style as the other feed action outlines.
 *
 * The old filled [FeedBookmark] next to reply/zap/like/repost outlines read as a different icon
 * family, so this is the unselected state; [FeedBookmarkFilled] remains the selected one.
 */
val PrimalIcons.FeedBookmarkOutline: ImageVector
    get() = ImageVector.Builder("FeedBookmarkOutline", 16.dp, 16.dp, 16f, 16f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF77727F)),
            strokeLineWidth = 1.55f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Left edge down from the rounded top-left corner, across the top, then down the right
            // edge to the ribbon's tail, which closes back through the V notch at the bottom.
            moveTo(3.7f, 2.9f)
            curveTo(3.7f, 2.3f, 4.15f, 1.9f, 4.7f, 1.9f)
            lineTo(11.3f, 1.9f)
            curveTo(11.85f, 1.9f, 12.3f, 2.3f, 12.3f, 2.9f)
            lineTo(12.3f, 13.6f)
            lineTo(8.3f, 10.75f)
            curveTo(8.12f, 10.62f, 7.88f, 10.62f, 7.7f, 10.75f)
            lineTo(3.7f, 13.6f)
            close()
        }
    }.build()
