@file:Suppress("MagicNumber")

package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.icons.PrimalIcons

val PrimalIcons.Algorithm: ImageVector
    get() {
        if (_algorithm != null) {
            return _algorithm!!
        }
        _algorithm = ImageVector.Builder(
            name = "Algorithm",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.5f, 20f)
                lineTo(12f, 4f)
                lineTo(19.5f, 20f)
                moveTo(7.4f, 14.2f)
                horizontalLineTo(16.6f)
            }
        }.build()
        return _algorithm!!
    }

private var _algorithm: ImageVector? = null
