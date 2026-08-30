package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons

/** Two compact opposing arrows communicate reposting without visual clutter. */
val PrimalIcons.FeedRepostsOutline: ImageVector
    get() = ImageVector.Builder("FeedRepostsOutline", 16.dp, 16.dp, 16f, 16f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF77727F)),
            strokeLineWidth = 1.55f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5.65f, 3.4f)
            horizontalLineTo(11.2f)
            curveTo(12.72f, 3.4f, 13.95f, 4.63f, 13.95f, 6.15f)
            verticalLineTo(8.4f)
            moveTo(5.65f, 3.4f)
            lineTo(7.15f, 1.95f)
            moveTo(5.65f, 3.4f)
            lineTo(7.15f, 4.85f)
            moveTo(10.35f, 12.6f)
            horizontalLineTo(4.8f)
            curveTo(3.28f, 12.6f, 2.05f, 11.37f, 2.05f, 9.85f)
            verticalLineTo(7.6f)
            moveTo(10.35f, 12.6f)
            lineTo(8.85f, 11.15f)
            moveTo(10.35f, 12.6f)
            lineTo(8.85f, 14.05f)
        }
    }.build()
