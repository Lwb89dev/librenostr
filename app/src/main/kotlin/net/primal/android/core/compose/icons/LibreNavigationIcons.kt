@file:Suppress("MagicNumber")

package net.primal.android.core.compose.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * LibreNostr's compact navigation family.
 *
 * The rounded 1.8 dp construction echoes the curves of the app mark while keeping every glyph
 * readable when Android applies a single theme tint at 24 dp.
 */
object LibreNavigationIcons {
    val Home: ImageVector by lazy {
        icon("LibreHome") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.4f, 10.5f)
                lineTo(12f, 3.8f)
                lineTo(20.6f, 10.5f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.5f, 9.3f)
                verticalLineTo(19.1f)
                curveTo(5.5f, 19.8f, 6.1f, 20.4f, 6.8f, 20.4f)
                horizontalLineTo(9.3f)
                verticalLineTo(15.2f)
                curveTo(9.3f, 14.5f, 9.9f, 13.9f, 10.6f, 13.9f)
                horizontalLineTo(13.4f)
                curveTo(14.1f, 13.9f, 14.7f, 14.5f, 14.7f, 15.2f)
                verticalLineTo(20.4f)
                horizontalLineTo(17.2f)
                curveTo(17.9f, 20.4f, 18.5f, 19.8f, 18.5f, 19.1f)
                verticalLineTo(9.3f)
            }
        }
    }

    val Notifications: ImageVector by lazy {
        icon("LibreNotifications") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.1f, 17.3f)
                curveTo(6.2f, 15.8f, 6.7f, 14.4f, 6.7f, 12.8f)
                verticalLineTo(9.8f)
                curveTo(6.7f, 6.8f, 9f, 4.5f, 12f, 4.5f)
                curveTo(15f, 4.5f, 17.3f, 6.8f, 17.3f, 9.8f)
                verticalLineTo(12.8f)
                curveTo(17.3f, 14.4f, 17.8f, 15.8f, 18.9f, 17.3f)
                curveTo(19.3f, 17.9f, 18.9f, 18.7f, 18.2f, 18.7f)
                horizontalLineTo(5.8f)
                curveTo(5.1f, 18.7f, 4.7f, 17.9f, 5.1f, 17.3f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(10f, 21f)
                curveTo(10.5f, 21.5f, 11.2f, 21.8f, 12f, 21.8f)
                curveTo(12.8f, 21.8f, 13.5f, 21.5f, 14f, 21f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(11.1f, 2.2f)
                horizontalLineTo(12.9f)
                curveTo(13.2f, 2.2f, 13.4f, 2.4f, 13.4f, 2.7f)
                curveTo(13.4f, 3f, 13.2f, 3.2f, 12.9f, 3.2f)
                horizontalLineTo(11.1f)
                curveTo(10.8f, 3.2f, 10.6f, 3f, 10.6f, 2.7f)
                curveTo(10.6f, 2.4f, 10.8f, 2.2f, 11.1f, 2.2f)
                close()
            }
        }
    }

    val Envelope: ImageVector by lazy {
        icon("LibreEnvelope") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3.5f, 5.5f); horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close()
                moveTo(4f, 6f); lineTo(12f, 13f); lineTo(20f, 6f)
            }
        }
    }

    val Menu: ImageVector by lazy {
        icon("LibreMenu") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(4f, 6.5f)
                horizontalLineTo(20f)
                moveTo(4f, 12f)
                horizontalLineTo(20f)
                moveTo(4f, 17.5f)
                horizontalLineTo(20f)
            }
        }
    }

    val Algorithm: ImageVector by lazy {
        icon("LibreAlgorithm") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 2.8f)
                curveTo(17.1f, 2.8f, 21.2f, 6.9f, 21.2f, 12f)
                curveTo(21.2f, 17.1f, 17.1f, 21.2f, 12f, 21.2f)
                curveTo(6.9f, 21.2f, 2.8f, 17.1f, 2.8f, 12f)
                curveTo(2.8f, 6.9f, 6.9f, 2.8f, 12f, 2.8f)
                close()
            }
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(16.9f, 7.1f)
                lineTo(14.5f, 13.2f)
                curveTo(14.3f, 13.8f, 13.8f, 14.3f, 13.2f, 14.5f)
                lineTo(7.1f, 16.9f)
                lineTo(9.5f, 10.8f)
                curveTo(9.7f, 10.2f, 10.2f, 9.7f, 10.8f, 9.5f)
                close()
                moveTo(12f, 10.8f)
                curveTo(11.3f, 10.8f, 10.8f, 11.3f, 10.8f, 12f)
                curveTo(10.8f, 12.7f, 11.3f, 13.2f, 12f, 13.2f)
                curveTo(12.7f, 13.2f, 13.2f, 12.7f, 13.2f, 12f)
                curveTo(13.2f, 11.3f, 12.7f, 10.8f, 12f, 10.8f)
                close()
            }
        }
    }

    /** Open-book glyph for the dedicated NIP-23 long-form reading feed. */
    val LongReads: ImageVector by lazy {
        icon("LibreLongReads") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 19.8f)
                curveTo(10.1f, 18.5f, 7.5f, 18.1f, 4.2f, 18.7f)
                verticalLineTo(5.1f)
                curveTo(7.5f, 4.5f, 10.1f, 4.9f, 12f, 6.2f)
                curveTo(13.9f, 4.9f, 16.5f, 4.5f, 19.8f, 5.1f)
                verticalLineTo(18.7f)
                curveTo(16.5f, 18.1f, 13.9f, 18.5f, 12f, 19.8f)
                close()
                moveTo(12f, 6.2f)
                verticalLineTo(19.8f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.7f, 8.3f)
                horizontalLineTo(9.5f)
                moveTo(14.5f, 8.3f)
                horizontalLineTo(17.3f)
                moveTo(6.7f, 11.2f)
                horizontalLineTo(9.5f)
                moveTo(14.5f, 11.2f)
                horizontalLineTo(17.3f)
            }
        }
    }

    val Profile: ImageVector by lazy {
        icon("LibreProfile") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 12f)
                arcToRelative(3.7f, 3.7f, 0f, true, true, 0.1f, 0f)
                arcToRelative(3.7f, 3.7f, 0f, true, true, -0.1f, 0f)
            }
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.2f, 20.2f)
                curveTo(5.7f, 16.9f, 8.3f, 15.3f, 12f, 15.3f)
                curveTo(15.7f, 15.3f, 18.3f, 16.9f, 19.8f, 20.2f)
            }
        }
    }

    val Gallery: ImageVector by lazy {
        icon("LibreGallery") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.2f, 4.2f)
                curveTo(4.2f, 3.4f, 4.8f, 2.8f, 5.6f, 2.8f)
                horizontalLineTo(18.4f)
                curveTo(19.2f, 2.8f, 19.8f, 3.4f, 19.8f, 4.2f)
                verticalLineTo(19.8f)
                curveTo(19.8f, 20.6f, 19.2f, 21.2f, 18.4f, 21.2f)
                horizontalLineTo(5.6f)
                curveTo(4.8f, 21.2f, 4.2f, 20.6f, 4.2f, 19.8f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(8.1f, 7.3f)
                curveTo(9.2f, 7.3f, 10.1f, 8.2f, 10.1f, 9.3f)
                curveTo(10.1f, 10.4f, 9.2f, 11.3f, 8.1f, 11.3f)
                curveTo(7f, 11.3f, 6.1f, 10.4f, 6.1f, 9.3f)
                curveTo(6.1f, 8.2f, 7f, 7.3f, 8.1f, 7.3f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.5f, 18f)
                lineTo(9.6f, 13.9f)
                curveTo(10.1f, 13.4f, 10.8f, 13.4f, 11.3f, 13.9f)
                lineTo(13.4f, 16f)
                lineTo(15.1f, 14.3f)
                curveTo(15.6f, 13.8f, 16.3f, 13.8f, 16.8f, 14.3f)
                lineTo(19.2f, 16.7f)
            }
        }
    }

    val Camera: ImageVector by lazy {
        icon("LibreCamera") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.3f, 7.5f)
                horizontalLineTo(8f)
                lineTo(9.4f, 5.2f)
                horizontalLineTo(14.6f)
                lineTo(16f, 7.5f)
                horizontalLineTo(19.7f)
                curveTo(20.5f, 7.5f, 21.2f, 8.2f, 21.2f, 9f)
                verticalLineTo(18.8f)
                curveTo(21.2f, 19.6f, 20.5f, 20.3f, 19.7f, 20.3f)
                horizontalLineTo(4.3f)
                curveTo(3.5f, 20.3f, 2.8f, 19.6f, 2.8f, 18.8f)
                verticalLineTo(9f)
                curveTo(2.8f, 8.2f, 3.5f, 7.5f, 4.3f, 7.5f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
            ) {
                moveTo(12f, 10.4f)
                moveToRelative(-3.5f, 3.5f)
                arcToRelative(3.5f, 3.5f, 0f, true, true, 7f, 0f)
                arcToRelative(3.5f, 3.5f, 0f, true, true, -7f, 0f)
            }
        }
    }

    /** Flat, detail-free ostrich outline used whenever a profile has no picture. */
    val Ostrich: ImageVector by lazy {
        icon("LibreOstrich") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.05f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Front-facing head and slender neck; deliberately no eyes or body details.
                moveTo(9.2f, 5.4f)
                curveTo(9.2f, 3.8f, 10.4f, 2.8f, 12f, 2.8f)
                curveTo(13.6f, 2.8f, 14.8f, 3.8f, 14.8f, 5.4f)
                curveTo(14.8f, 7.1f, 13.7f, 8.2f, 12.8f, 8.7f)
                curveTo(12.4f, 9.1f, 12.4f, 10.1f, 12.7f, 11.3f)
                curveTo(13.1f, 13.5f, 14.2f, 15.7f, 15.2f, 18.7f)
                curveTo(14.2f, 20.1f, 13.1f, 20.8f, 12f, 20.8f)
                curveTo(10.9f, 20.8f, 9.8f, 20.1f, 8.8f, 18.7f)
                curveTo(9.8f, 15.7f, 10.9f, 13.5f, 11.3f, 11.3f)
                curveTo(11.6f, 10.1f, 11.6f, 9.1f, 11.2f, 8.7f)
                curveTo(10.3f, 8.2f, 9.2f, 7.1f, 9.2f, 5.4f)
                close()
            }
        }
    }

    private fun statIcon(name: String, glyph: Int): ImageVector = icon(name) {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (glyph) {
                0 -> { moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close(); moveTo(7f, 9f); horizontalLineTo(17f); moveTo(7f, 13f); horizontalLineTo(14f) }
                1 -> { moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(16f); horizontalLineTo(13f); lineTo(9f, 20f); verticalLineTo(16f); horizontalLineTo(4f); close() }
                2 -> { moveTo(12f, 4f); curveTo(16.4f, 4f, 20f, 7.6f, 20f, 12f); curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f); curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f); curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f); close(); moveTo(12f, 8f); verticalLineTo(12f); lineTo(15f, 14f) }
                else -> { moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close(); moveTo(7f, 15f); lineTo(10f, 12f); lineTo(13f, 14f); lineTo(17f, 9f) }
            }
        }
    }
    val Notes: ImageVector by lazy { statIcon("LibreNotes", 0) }
    val Replies: ImageVector by lazy { statIcon("LibreReplies", 1) }
    val Reads: ImageVector by lazy { statIcon("LibreReads", 2) }
    val Media: ImageVector by lazy { statIcon("LibreMedia", 3) }

    val Settings: ImageVector by lazy {
        icon("LibreSettings") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(3f, 6.2f)
                horizontalLineTo(8.2f)
                moveTo(12.2f, 6.2f)
                horizontalLineTo(21f)
                moveTo(3f, 12f)
                horizontalLineTo(12.8f)
                moveTo(16.8f, 12f)
                horizontalLineTo(21f)
                moveTo(3f, 17.8f)
                horizontalLineTo(6.2f)
                moveTo(10.2f, 17.8f)
                horizontalLineTo(21f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
            ) {
                moveTo(10.2f, 4.2f)
                moveToRelative(-2f, 2f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                moveTo(14.8f, 10f)
                moveToRelative(-2f, 2f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                moveTo(8.2f, 15.8f)
                moveToRelative(-2f, 2f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
            }
        }
    }

    private fun icon(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(paths).build()
}
