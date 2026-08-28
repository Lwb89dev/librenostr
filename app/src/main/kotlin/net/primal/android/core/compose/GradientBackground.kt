package net.primal.android.core.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val GradientColorLight = Color(0xFFD7C6F8)
private val GradientColorMid = Color(0xFFC4A8F4)
private val GradientColorDeep = Color(0xFFB08AEF)

fun primalGradientBrush(size: Size) =
    Brush.verticalGradient(
        colors = listOf(GradientColorLight, GradientColorMid, GradientColorDeep),
        startY = 0f,
        endY = size.height,
    )

const val PrimalGradientAlpha = 0.85f
val PrimalGradientBackgroundColor = Color(0xFFD7C6F8)
val PrimalDarkTextColor = Color(0xFF111111)
val PrimalSecondaryTextColor = Color(0xFF666666)
val PrimalDarkButtonColor = Color(0xFF252628)
