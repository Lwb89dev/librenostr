package net.primal.android.core.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.bubble.anchor
import net.primal.android.core.compose.icons.LibreNavigationIcons
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.theme.AppTheme
import net.primal.android.user.domain.Badges
import net.primal.domain.links.CdnImage

val NavigationBarFullHeightDp = 64.dp

@Composable
@Suppress("LongMethod")
fun PrimalNavigationBar(
    modifier: Modifier = Modifier,
    activeDestination: PrimalTopLevelDestination,
    onTopLevelDestinationChanged: (PrimalTopLevelDestination) -> Unit,
    onActiveDestinationClick: (() -> Unit)? = null,
    onMessagesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    profileAvatarCdnImage: CdnImage? = null,
    profileLabel: String = "Profile",
    profileSelected: Boolean = false,
    settingsSelected: Boolean = false,
    badges: Badges = Badges(),
    exploreAnchorHandle: AnchorHandle? = null,
) {
    val badgesMap = mapOf(
        Pair(PrimalTopLevelDestination.Alerts, badges.unreadNotificationsCount),
    )

    Surface(color = Color.Transparent) {
        Column(modifier = modifier) {
            PrimalDivider()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = AppTheme.colorScheme.surface)
                    .height(NavigationBarFullHeightDp),
            ) {
                val horizontalPadding = 12.dp
                val topPadding = 4.dp
                val pillWidth = 72.dp
                // Keep the profile button centered while using the former algorithm
                // slot for the direct-message inbox.
                val destinations = listOf(
                    PrimalTopLevelDestination.Feeds,
                    PrimalTopLevelDestination.Messages,
                    PrimalTopLevelDestination.Alerts,
                    PrimalTopLevelDestination.Settings,
                )
                val visualSelected = if (settingsSelected) {
                    PrimalTopLevelDestination.Settings
                } else {
                    activeDestination
                }
                val itemCount = destinations.size + 1
                val itemWidth = (maxWidth - horizontalPadding * 2) / itemCount
                val selectedIndex = if (profileSelected) {
                    // The profile item is inserted between messages and alerts.
                    2
                } else {
                    (destinations.indexOf(visualSelected) +
                        if (destinations.indexOf(visualSelected) >= 2) 1 else 0).coerceAtLeast(0)
                }

                val pillOffset by animateDpAsState(
                    targetValue = horizontalPadding + itemWidth * selectedIndex + (itemWidth - pillWidth) / 2,
                    animationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "pillOffset",
                )

                Box(
                    modifier = Modifier
                        .offset(x = pillOffset, y = topPadding)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .height(52.dp)
                            .width(pillWidth)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        AppTheme.colorScheme.primary.copy(alpha = 0.24f),
                                        AppTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    ),
                                ),
                                shape = CircleShape,
                            )
                            .border(
                                width = 1.dp,
                                color = AppTheme.colorScheme.primary.copy(alpha = 0.28f),
                                shape = CircleShape,
                            ),
                    )
                }

                Row(
                    modifier = Modifier
                        .padding(top = topPadding)
                        .padding(horizontal = horizontalPadding)
                        .fillMaxSize(),
                ) {
                    destinations.forEachIndexed { index, destination ->
                        if (index == 2) {
                            PrimalProfileNavigationBarItem(
                                modifier = Modifier.weight(1f),
                                label = profileLabel,
                                selected = profileSelected,
                                avatarCdnImage = profileAvatarCdnImage,
                                onClick = onProfileClick,
                            )
                        }
                        PrimalNavigationBarItem(
                            modifier = Modifier
                                .weight(1f)
                                .anchorIfExplore(destination, exploreAnchorHandle),
                            destination = destination,
                            selected = destination != PrimalTopLevelDestination.Messages &&
                                destination == visualSelected,
                            badge = badgesMap.getOrDefault(destination, 0),
                            onClick = {
                                if (destination == PrimalTopLevelDestination.Messages) {
                                    onMessagesClick()
                                } else if (destination == PrimalTopLevelDestination.Settings) {
                                    onSettingsClick()
                                } else if (activeDestination != destination) {
                                    onTopLevelDestinationChanged(destination)
                                } else {
                                    onActiveDestinationClick?.invoke()
                                }
                            },
                        )
                    }
                }
            }

            val navBarHeight = with(LocalDensity.current) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            Spacer(
                modifier = Modifier
                    .background(color = AppTheme.colorScheme.surface)
                    .fillMaxWidth()
                    .height(navBarHeight),
            )
        }
    }
}

@Composable
private fun PrimalProfileNavigationBarItem(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    avatarCdnImage: CdnImage?,
    onClick: () -> Unit,
) {
    val tint = if (selected) AppTheme.colorScheme.primary else AppTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .padding(top = 4.dp)
            .clickable(indication = null, interactionSource = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        UniversalAvatarThumbnail(
            avatarCdnImage = avatarCdnImage,
            avatarSize = 24.dp,
            hasBorder = false,
            fallbackBorderColor = tint,
            onClick = null,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = AppTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 10.sp), color = tint)
    }
}

@Composable
private fun PrimalNavigationBarItem(
    modifier: Modifier = Modifier,
    destination: PrimalTopLevelDestination,
    selected: Boolean,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        AppTheme.colorScheme.primary
    } else {
        AppTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .padding(top = 4.dp)
            .clickable(indication = null, interactionSource = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(
            badge = {
                if (badge > 0) {
                    Badge(containerColor = AppTheme.colorScheme.primary)
                }
            },
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = destination.imageVector(),
                contentDescription = destination.label(),
                tint = tint,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = destination.label(),
            style = AppTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 10.sp),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.anchorIfExplore(destination: PrimalTopLevelDestination, handle: AnchorHandle?): Modifier =
    if (destination == PrimalTopLevelDestination.Explore && handle != null) anchor(handle = handle) else this

enum class PrimalTopLevelDestination {
    Feeds,
    Reads,
    Wallet,
    Messages,
    Alerts,
    Explore,
    Settings,
}

private fun PrimalTopLevelDestination.imageVector(): ImageVector {
    return when (this) {
        PrimalTopLevelDestination.Feeds -> LibreNavigationIcons.Home
        PrimalTopLevelDestination.Reads -> LibreNavigationIcons.Home
        PrimalTopLevelDestination.Wallet -> LibreNavigationIcons.Settings
        PrimalTopLevelDestination.Alerts -> LibreNavigationIcons.Notifications
        PrimalTopLevelDestination.Messages -> LibreNavigationIcons.Envelope
        PrimalTopLevelDestination.Explore -> LibreNavigationIcons.Algorithm
        PrimalTopLevelDestination.Settings -> LibreNavigationIcons.Settings
    }
}

@Composable
private fun PrimalTopLevelDestination.label(): String {
    return when (this) {
        PrimalTopLevelDestination.Feeds -> stringResource(id = R.string.primary_destination_feed_label)
        PrimalTopLevelDestination.Reads -> stringResource(id = R.string.primary_destination_reads_label)
        PrimalTopLevelDestination.Wallet -> stringResource(id = R.string.primary_destination_wallet_label)
        PrimalTopLevelDestination.Alerts -> stringResource(id = R.string.primary_destination_notifications_label)
        PrimalTopLevelDestination.Messages -> stringResource(id = R.string.primary_destination_messages_label)
        PrimalTopLevelDestination.Explore -> stringResource(id = R.string.primary_destination_explore_label)
        PrimalTopLevelDestination.Settings -> stringResource(id = R.string.drawer_destination_settings)
    }
}

@Preview
@Composable
fun PreviewNavigationBar() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        Surface(modifier = Modifier.wrapContentSize()) {
            PrimalNavigationBar(
                activeDestination = PrimalTopLevelDestination.Feeds,
                onTopLevelDestinationChanged = {},
            )
        }
    }
}

@Preview
@Composable
fun PreviewNavigationBarReads() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        Surface(modifier = Modifier.wrapContentSize()) {
            PrimalNavigationBar(
                activeDestination = PrimalTopLevelDestination.Reads,
                badges = Badges(unreadNotificationsCount = 1),
                onTopLevelDestinationChanged = {},
            )
        }
    }
}

@Preview
@Composable
fun PreviewNavigationBarWallet() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        Surface(modifier = Modifier.wrapContentSize()) {
            PrimalNavigationBar(
                activeDestination = PrimalTopLevelDestination.Wallet,
                badges = Badges(unreadNotificationsCount = 1),
                onTopLevelDestinationChanged = {},
            )
        }
    }
}

@Preview
@Composable
fun PreviewNavigationBarAlerts() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        Surface(modifier = Modifier.wrapContentSize()) {
            PrimalNavigationBar(
                activeDestination = PrimalTopLevelDestination.Alerts,
                badges = Badges(unreadNotificationsCount = 1),
                onTopLevelDestinationChanged = {},
            )
        }
    }
}

@Preview
@Composable
fun PreviewNavigationBarExplore() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        Surface(modifier = Modifier.wrapContentSize()) {
            PrimalNavigationBar(
                activeDestination = PrimalTopLevelDestination.Explore,
                badges = Badges(unreadNotificationsCount = 1),
                onTopLevelDestinationChanged = {},
            )
        }
    }
}
