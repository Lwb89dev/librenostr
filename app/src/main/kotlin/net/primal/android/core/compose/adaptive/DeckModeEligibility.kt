package net.primal.android.core.compose.adaptive

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// Same threshold Android resource qualifiers use for "large screen" (values-sw600dp).
private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

/**
 * True on devices whose smallest-width dimension makes them a tablet, regardless of the
 * current orientation. `smallestScreenWidthDp` is a device property (not the current window
 * size), so it stays true in split-screen even when the visible window is narrow.
 */
@Composable
fun rememberIsTabletDevice(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
}

/**
 * Deck mode is only for tablets held in landscape. Phones never qualify, even rotated.
 */
@Composable
fun rememberIsDeckModeEligible(): Boolean {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return rememberIsTabletDevice() && isLandscape
}
