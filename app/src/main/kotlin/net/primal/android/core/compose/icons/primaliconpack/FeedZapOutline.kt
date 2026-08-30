package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons

/** Outlined lightning mark that stays legible at compact action sizes. */
val PrimalIcons.FeedZapOutline: ImageVector
    get() = ImageVector.Builder("FeedZapOutline", 16.dp, 16.dp, 16f, 16f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF77727F)),
            strokeLineWidth = 1.55f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9.7f, 1.5f)
            lineTo(3.35f, 8.6f)
            lineTo(7.55f, 8.6f)
            lineTo(6.3f, 14.5f)
            lineTo(12.65f, 7.4f)
            lineTo(8.45f, 7.4f)
            close()
        }
    }.build()
