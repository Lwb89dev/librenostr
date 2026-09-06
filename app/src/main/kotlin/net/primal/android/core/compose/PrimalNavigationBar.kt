package net.primal.android.core.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.icons.LibreNavigationIcons
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.theme.AppTheme
import net.primal.android.theme.LibreNostrTokens
import net.primal.android.user.domain.Badges

// Callers that need to reserve space for the dock (e.g. PrimalMainScaffold's height-stability
// check, WalletDashboardContent's footer spacer) need this to match what's actually composed
// below: 8dp top padding + the dock's own height. Keep in sync if either changes.
val NavigationBarFullHeightDp = 8.dp + LibreNostrTokens.DEFAULT_DOCK_HEIGHT_DP.dp

/** A detached, accent-aware navigation dock that preserves the existing navigation callbacks. */
@Suppress("UnusedParameter")
@Composable
fun PrimalNavigationBar(
    modifier: Modifier = Modifier,
    activeDestination: PrimalTopLevelDestination,
    onTopLevelDestinationChanged: (PrimalTopLevelDestination) -> Unit,
    onActiveDestinationClick: (() -> Unit)? = null,
    onMessagesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    settingsSelected: Boolean = false,
    badges: Badges = Badges(),
    // Kept for API compatibility with callers still passing one, but currently inert: this dock
    // no longer has a slot for PrimalTopLevelDestination.Explore to attach a bubble/tutorial to.
    exploreAnchorHandle: AnchorHandle? = null,
    composeAction: (@Composable () -> Unit)? = null,
) {
    val tokens = AppTheme.libreNostrTokens
    val visualSelected = if (settingsSelected) PrimalTopLevelDestination.Settings else activeDestination
    val navigationInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

    Surface(color = AppTheme.colorScheme.background) {
        Column(modifier = modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
                    .height(tokens.dockHeight),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tokens.dockHeight)
                        .shadow(elevation = 14.dp, shape = RoundedCornerShape(28.dp), clip = false)
                        .clip(RoundedCornerShape(28.dp))
                        .background(tokens.dockSurface)
                        .border(1.dp, tokens.softOutline, RoundedCornerShape(28.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DockDestinationItem(
                        modifier = Modifier.weight(1f),
                        destination = PrimalTopLevelDestination.Feeds,
                        selected = visualSelected == PrimalTopLevelDestination.Feeds,
                        badge = badges.unreadFeedCount,
                        onClick = {
                            if (activeDestination == PrimalTopLevelDestination.Feeds) {
                                onActiveDestinationClick?.invoke()
                            } else {
                                onTopLevelDestinationChanged(PrimalTopLevelDestination.Feeds)
                            }
                        },
                    )
                    DockDestinationItem(
                        modifier = Modifier.weight(1f),
                        destination = PrimalTopLevelDestination.Messages,
                        selected = false,
                        badge = badges.unreadMessagesCount,
                        onClick = onMessagesClick,
                    )

                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        composeAction?.invoke()
                    }

                    DockDestinationItem(
                        modifier = Modifier.weight(1f),
                        destination = PrimalTopLevelDestination.Alerts,
                        selected = visualSelected == PrimalTopLevelDestination.Alerts,
                        badge = badges.unreadNotificationsCount,
                        onClick = {
                            if (activeDestination == PrimalTopLevelDestination.Alerts) {
                                onActiveDestinationClick?.invoke()
                            } else {
                                onTopLevelDestinationChanged(PrimalTopLevelDestination.Alerts)
                            }
                        },
                    )
                    DockDestinationItem(
                        modifier = Modifier.weight(1f),
                        destination = PrimalTopLevelDestination.Settings,
                        selected = visualSelected == PrimalTopLevelDestination.Settings,
                        onClick = onSettingsClick,
                    )
                }
            }
            Spacer(modifier = Modifier.height(navigationInset))
        }
    }
}

@Composable
private fun DockDestinationItem(
    modifier: Modifier = Modifier,
    destination: PrimalTopLevelDestination,
    selected: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val tokens = AppTheme.libreNostrTokens
    // Messages intentionally remains neutral: a direct-message inbox is not a selected tab.
    val tint = when {
        destination == PrimalTopLevelDestination.Messages -> AppTheme.colorScheme.onSurface
        selected -> tokens.accent
        else -> AppTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    val selectionOffset by animateDpAsState(
        targetValue = if (selected) 0.dp else 3.dp,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "DockDestinationOffset",
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(horizontal = 2.dp)
            .clip(CircleShape)
            .background(if (selected) tokens.accentSoft else Color.Transparent, CircleShape)
            .clickable(indication = null, interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(
            badge = {
                if (badge > 0) Badge(containerColor = tokens.accent, contentColor = AppTheme.colorScheme.onPrimary)
            },
        ) {
            Icon(
                modifier = Modifier
                    .size(24.dp)
                    .padding(bottom = selectionOffset),
                imageVector = destination.imageVector(),
                contentDescription = destination.label(),
                tint = tint,
            )
        }
    }
}

enum class PrimalTopLevelDestination {
    Feeds,
    Reads,
    Wallet,
    Messages,
    Alerts,
    Explore,
    Settings,
}

private fun PrimalTopLevelDestination.imageVector(): ImageVector =
    when (this) {
        PrimalTopLevelDestination.Feeds, PrimalTopLevelDestination.Reads -> LibreNavigationIcons.Home
        PrimalTopLevelDestination.Wallet, PrimalTopLevelDestination.Settings -> LibreNavigationIcons.Settings
        PrimalTopLevelDestination.Alerts -> LibreNavigationIcons.Notifications
        PrimalTopLevelDestination.Messages -> LibreNavigationIcons.Envelope
        PrimalTopLevelDestination.Explore -> LibreNavigationIcons.Algorithm
    }

@Composable
private fun PrimalTopLevelDestination.label(): String =
    when (this) {
        PrimalTopLevelDestination.Feeds -> stringResource(id = R.string.primary_destination_feed_label)
        PrimalTopLevelDestination.Reads -> stringResource(id = R.string.primary_destination_reads_label)
        PrimalTopLevelDestination.Wallet -> stringResource(id = R.string.primary_destination_wallet_label)
        PrimalTopLevelDestination.Alerts -> stringResource(id = R.string.primary_destination_notifications_label)
        PrimalTopLevelDestination.Messages -> stringResource(id = R.string.primary_destination_messages_label)
        PrimalTopLevelDestination.Explore -> stringResource(id = R.string.primary_destination_explore_label)
        PrimalTopLevelDestination.Settings -> stringResource(id = R.string.drawer_destination_settings)
    }

@Preview
@Composable
private fun PreviewNavigationBar() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        PrimalNavigationBar(activeDestination = PrimalTopLevelDestination.Feeds, onTopLevelDestinationChanged = {})
    }
}
