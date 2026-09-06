package net.primal.android.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visual primitives shared by LibreNostr surfaces.
 *
 * Values intentionally derive from the selected [androidx.compose.material3.ColorScheme], so
 * any future user-provided accent automatically flows through the main UI without screen-level
 * colour constants.
 */
@Immutable
data class LibreNostrTokens(
    val accent: Color,
    val accentSecondary: Color,
    val accentTertiary: Color,
    val accentSoft: Color,
    val accentSubtle: Color,
    val elevatedSurface: Color,
    val sunkenSurface: Color,
    val cardSurface: Color,
    val dockSurface: Color,
    val softOutline: Color,
    val contentSpacing: Dp = 16.dp,
    val feedSpacing: Dp = 10.dp,
    val dockHeight: Dp = DEFAULT_DOCK_HEIGHT_DP.dp,
) {
    companion object {
        // Exposed as a raw Int (rather than just a private default) so callers that need to
        // reserve layout space for the dock outside a Composable — where rememberLibreNostrTokens()
        // isn't reachable — can still stay in sync with it. See PrimalNavigationBar's
        // NavigationBarFullHeightDp.
        const val DEFAULT_DOCK_HEIGHT_DP = 68
    }
}

@Composable
fun rememberLibreNostrTokens(): LibreNostrTokens {
    val colors = AppTheme.colorScheme
    val extra = AppTheme.extraColorScheme

    return remember(colors, extra) {
        LibreNostrTokens(
            accent = colors.primary,
            accentSecondary = colors.secondary,
            accentTertiary = colors.tertiary,
            accentSoft = colors.primary.copy(alpha = 0.18f),
            accentSubtle = colors.primary.copy(alpha = 0.08f),
            elevatedSurface = extra.surfaceVariantAlt2,
            sunkenSurface = extra.surfaceVariantAlt3,
            cardSurface = colors.surfaceVariant,
            dockSurface = colors.surfaceVariant.copy(alpha = 0.96f),
            softOutline = colors.outline.copy(alpha = 0.56f),
        )
    }
}
