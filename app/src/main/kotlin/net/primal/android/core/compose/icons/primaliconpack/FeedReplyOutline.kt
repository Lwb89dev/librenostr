package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons

/** Lightweight rounded reply bubble used by note action rows. */
val PrimalIcons.FeedReplyOutline: ImageVector
    get() = ImageVector.Builder("FeedReplyOutline", 16.dp, 16.dp, 16f, 16f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF77727F)),
            strokeLineWidth = 1.55f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3.2f, 10.15f)
            lineTo(2.65f, 13.55f)
            lineTo(5.85f, 11.6f)
            curveTo(6.55f, 11.85f, 7.28f, 11.98f, 8f, 11.98f)
            curveTo(11.72f, 11.98f, 14.65f, 9.78f, 14.65f, 7.05f)
            curveTo(14.65f, 4.32f, 11.72f, 2.12f, 8f, 2.12f)
            curveTo(4.28f, 2.12f, 1.35f, 4.32f, 1.35f, 7.05f)
            curveTo(1.35f, 8.28f, 2.03f, 9.39f, 3.2f, 10.15f)
        }
    }.build()
