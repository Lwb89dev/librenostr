package net.primal.android.core.compose

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import net.primal.android.core.compose.adaptive.rememberIsTabletDevice

/**
 * No-op on tablets: every screen in this app calls this on entry, which is correct for phones
 * (the whole app is phone-only-portrait outside deck mode) but would otherwise yank a tablet back
 * to portrait the moment you navigate off deck mode's screen — undoing the landscape unlock deck
 * mode depends on for every other destination reachable from it (thread, profile, editor, ...).
 */
@Composable
fun LockToOrientationPortrait() {
    if (rememberIsTabletDevice()) return
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
}

@Composable
fun LockToOrientationLandscape() = LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

@Composable
fun UnlockScreenOrientation() = LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)

@Composable
fun LockScreenOrientation(orientation: Int) {
    val activity = LocalActivity.current
    if (activity != null) {
        LaunchedEffect(orientation) {
            activity.requestedOrientation = orientation
        }
    }
}
