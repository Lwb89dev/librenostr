package net.primal.android.core.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
// check) need this to match what's actually composed below: 8dp top padding + the dock's own
// height. Keep in sync if either changes.
val NavigationBarFullHeightDp = 8.dp + LibreNostrTokens.DEFAULT_DOCK_HEIGHT_DP.dp

/** A detached, accent-aware navigation dock that preserves the existing navigation callbacks. */
@Suppress("LongMethod", "UnusedParameter")
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
    val visualSelected = when {
        settingsSelected -> PrimalTopLevelDestination.Settings
        activeDestination == PrimalTopLevelDestination.Reads -> PrimalTopLevelDestination.Feeds
        activeDestination == PrimalTopLevelDestination.Explore -> PrimalTopLevelDestination.Feeds
        else -> activeDestination
    }
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

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        composeAction?.invoke()
                        Text(
                            text = stringResource(id = R.string.primary_destination_post_label),
                            style = AppTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = tokens.accent,
                            maxLines = 1,
                        )
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
@Suppress("MagicNumber")
private fun DockDestinationItem(
    modifier: Modifier = Modifier,
    destination: PrimalTopLevelDestination,
    selected: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val tokens = AppTheme.libreNostrTokens
    val targetTint = when {
        selected -> tokens.accent
        else -> AppTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    val tint by animateColorAsState(targetValue = targetTint, label = "DockItemTint")
    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) tokens.accentSoft else AppTheme.colorScheme.surface,
        label = "DockItemContainer",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DockItemScale",
    )

    Column(
        modifier = modifier
            .height(60.dp)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 32.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .background(
                    color = iconContainerColor,
                    shape = RoundedCornerShape(13.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(
                badge = {
                    if (badge > 0) {
                        Badge(containerColor = tokens.accent, contentColor = AppTheme.colorScheme.onPrimary)
                    }
                },
            ) {
                Icon(
                    modifier = Modifier.size(23.dp),
                    imageVector = destination.imageVector(),
                    contentDescription = destination.label(),
                    tint = tint,
                )
            }
        }
        Text(
            text = destination.shortLabel(),
            style = AppTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

enum class PrimalTopLevelDestination {
    Feeds,
    Reads,
    Messages,
    Alerts,
    Explore,
    Settings,
}

private fun PrimalTopLevelDestination.imageVector(): ImageVector =
    when (this) {
        PrimalTopLevelDestination.Feeds, PrimalTopLevelDestination.Reads -> LibreNavigationIcons.Home
        PrimalTopLevelDestination.Settings -> LibreNavigationIcons.Settings
        PrimalTopLevelDestination.Alerts -> LibreNavigationIcons.Notifications
        PrimalTopLevelDestination.Messages -> LibreNavigationIcons.Envelope
        PrimalTopLevelDestination.Explore -> LibreNavigationIcons.Algorithm
    }

@Composable
private fun PrimalTopLevelDestination.label(): String =
    when (this) {
        PrimalTopLevelDestination.Feeds -> stringResource(id = R.string.primary_destination_feed_label)
        PrimalTopLevelDestination.Reads -> stringResource(id = R.string.primary_destination_reads_label)
        PrimalTopLevelDestination.Alerts -> stringResource(id = R.string.primary_destination_notifications_label)
        PrimalTopLevelDestination.Messages -> stringResource(id = R.string.primary_destination_messages_label)
        PrimalTopLevelDestination.Explore -> stringResource(id = R.string.primary_destination_explore_label)
        PrimalTopLevelDestination.Settings -> stringResource(id = R.string.drawer_destination_settings)
    }

@Composable
private fun PrimalTopLevelDestination.shortLabel(): String =
    when (this) {
        PrimalTopLevelDestination.Messages ->
            stringResource(id = R.string.primary_destination_messages_short_label)
        PrimalTopLevelDestination.Alerts ->
            stringResource(id = R.string.primary_destination_notifications_short_label)
        else -> label()
    }

@Preview
@Composable
private fun PreviewNavigationBar() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        PrimalNavigationBar(activeDestination = PrimalTopLevelDestination.Feeds, onTopLevelDestinationChanged = {})
    }
}
