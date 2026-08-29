@file:Suppress("MagicNumber")

package net.primal.android.core.compose.icons.primaliconpack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
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
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(12f, 2.2f)
                lineTo(21.4f, 21.2f)
                horizontalLineTo(17.55f)
                lineTo(15.55f, 16.9f)
                horizontalLineTo(8.45f)
                lineTo(6.45f, 21.2f)
                horizontalLineTo(2.6f)
                close()
                moveTo(12f, 7.35f)
                lineTo(14.15f, 12.15f)
                horizontalLineTo(9.85f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 13.55f)
                moveToRelative(-1.85f, 0f)
                arcToRelative(1.85f, 1.85f, 0f, true, true, 3.7f, 0f)
                arcToRelative(1.85f, 1.85f, 0f, true, true, -3.7f, 0f)
            }
        }.build()
        return _algorithm!!
    }

private var _algorithm: ImageVector? = null
