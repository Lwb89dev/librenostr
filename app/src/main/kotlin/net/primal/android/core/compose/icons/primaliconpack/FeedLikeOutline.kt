package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons

/** Simple heart outline with a soft, modern silhouette. */
val PrimalIcons.FeedLikeOutline: ImageVector
    get() = ImageVector.Builder("FeedLikeOutline", 16.dp, 16.dp, 16f, 16f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF77727F)),
            strokeLineWidth = 1.55f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8f, 13.85f)
            lineTo(3.05f, 8.95f)
            curveTo(0.45f, 6.38f, 2.12f, 2.18f, 5.55f, 2.18f)
            curveTo(6.62f, 2.18f, 7.55f, 2.75f, 8f, 3.62f)
            curveTo(8.45f, 2.75f, 9.38f, 2.18f, 10.45f, 2.18f)
            curveTo(13.88f, 2.18f, 15.55f, 6.38f, 12.95f, 8.95f)
            lineTo(8f, 13.85f)
        }
    }.build()
