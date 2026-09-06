package net.primal.android.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import net.primal.android.core.compose.PrimalTopLevelDestination

val primalSlideInHorizontallyFromEnd = slideInHorizontally(animationSpec = tween(), initialOffsetX = { it })

val primalSlideOutHorizontallyToEnd = slideOutHorizontally(animationSpec = tween(), targetOffsetX = { it })

val primalSlideInHorizontallyFromStart = slideInHorizontally(animationSpec = tween(), initialOffsetX = { -it })

val primalSlideOutHorizontallyToStart = slideOutHorizontally(animationSpec = tween(), targetOffsetX = { -it })

val primalScaleIn = scaleIn(animationSpec = tween(), initialScale = 0.9f)

val primalScaleOut = scaleOut(animationSpec = tween(), targetScale = 0.9f)

/**
 * Key "main" writes its currently-displayed tab under, in its own [NavBackStackEntry.savedStateHandle]
 * (see MainScreen.kt) — separate from [net.primal.android.main.REQUESTED_TAB_KEY], which only ever
 * carries a one-shot request and is cleared right after being read. This one instead always reflects
 * what's on screen, so a transition leaving "main" can tell whether it was showing Home or
 * Notifications.
 */
internal const val CURRENT_MAIN_TAB_KEY = "currentMainTab"

/**
 * Left-to-right order of the bottom nav's tab-switch destinations, used to pick a consistent
 * slide direction between them. Deliberately excludes "profile": that route also serves as the
 * generic "view any user's profile" screen reached by drilling into a note, and retrofitting
 * tab-position awareness onto it would misfire for that far more common case — profile keeps its
 * existing forward-navigation transitions untouched.
 */
private const val TAB_INDEX_HOME = 0
private const val TAB_INDEX_MESSAGES = 1
private const val TAB_INDEX_NOTIFICATIONS = 2
private const val TAB_INDEX_SETTINGS = 3

private fun NavBackStackEntry.primaryTabIndex(): Int? =
    when (destination.route) {
        "main" -> when (savedStateHandle.get<String>(CURRENT_MAIN_TAB_KEY)) {
            PrimalTopLevelDestination.Alerts.name -> TAB_INDEX_NOTIFICATIONS
            else -> TAB_INDEX_HOME
        }
        "messages" -> TAB_INDEX_MESSAGES
        "home_settings" -> TAB_INDEX_SETTINGS
        else -> null
    }

/**
 * Whether a transition between two tab-switch destinations should slide right-to-left (moving to
 * a tab further right) as opposed to left-to-right (moving to one further left). Null when either
 * side isn't one of the tab-switch destinations, or they resolve to the same tab (e.g. Home and
 * Notifications both live under the "main" route, so leaving Home for some unrelated screen and
 * coming back to Notifications is not, itself, a tab-to-tab move) — callers fall back to their
 * existing transition in that case.
 */
internal fun slidesRightToLeft(from: NavBackStackEntry, to: NavBackStackEntry): Boolean? {
    val fromIndex = from.primaryTabIndex()
    val toIndex = to.primaryTabIndex()
    return if (fromIndex == null || toIndex == null || fromIndex == toIndex) null else toIndex > fromIndex
}

/**
 * Resolves [slidesRightToLeft] into an actual [EnterTransition], centralizing the mapping so each
 * destination's four transition lambdas can stay a one-liner instead of repeating the same `when`
 * (which was pushing their enclosing NavGraphBuilder functions well past the complexity a single
 * function should carry).
 */
internal fun enterTransitionFor(rightToLeft: Boolean?, fallback: EnterTransition? = null): EnterTransition? =
    when (rightToLeft) {
        true -> primalSlideInHorizontallyFromEnd
        false -> primalSlideInHorizontallyFromStart
        null -> fallback
    }

/** Exit-side counterpart to [enterTransitionFor] — see its doc for why this is centralized. */
internal fun exitTransitionFor(rightToLeft: Boolean?, fallback: ExitTransition? = null): ExitTransition? =
    when (rightToLeft) {
        true -> primalSlideOutHorizontallyToStart
        false -> primalSlideOutHorizontallyToEnd
        null -> fallback
    }
