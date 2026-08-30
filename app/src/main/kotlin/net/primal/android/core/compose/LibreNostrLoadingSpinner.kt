package net.primal.android.core.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.primal.android.theme.AppTheme

@Composable
fun LibreNostrLoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val transition = rememberInfiniteTransition(label = "librenostr-loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "librenostr-loading-rotation",
    )
    val colors = listOf(
        AppTheme.extraColorScheme.onBrand,
        AppTheme.colorScheme.primary,
        AppTheme.extraColorScheme.replied,
    )

    Canvas(modifier = modifier.size(size)) {
        rotate(rotation) {
            drawArc(
                brush = Brush.sweepGradient(colors),
                startAngle = 25f,
                sweepAngle = 290f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
