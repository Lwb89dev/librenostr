package net.primal.android.settings.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.icons.primaliconpack.ConnectRelay
import net.primal.android.core.compose.icons.primaliconpack.DarkMode
import net.primal.android.core.compose.icons.primaliconpack.FontSize
import net.primal.android.core.compose.icons.primaliconpack.ImportPhotoFromGallery
import net.primal.android.core.compose.icons.primaliconpack.Key
import net.primal.android.core.compose.icons.primaliconpack.MenuAccount
import net.primal.android.core.compose.icons.primaliconpack.MuteUser
import net.primal.android.core.compose.icons.primaliconpack.Notifications
import net.primal.android.core.compose.icons.primaliconpack.NostrConnectSession
import net.primal.android.core.compose.icons.primaliconpack.NavWallet
import net.primal.android.core.compose.icons.primaliconpack.Zap
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.theme.AppTheme
import net.primal.android.theme.domain.PrimalTheme

@Composable
fun SettingsHomeScreen(
    viewModel: SettingsHomeViewModel,
    onClose: () -> Unit,
    onSettingsSectionClick: (PrimalSettingsSection) -> Unit,
    onDeveloperToolsClick: () -> Unit,
    sectionContent: (@Composable (PrimalSettingsSection) -> Unit)? = null,
    developerContent: (@Composable () -> Unit)? = null,
) {
    val uiState = viewModel.state.collectAsState()

    SettingsHomeScreen(
        state = uiState.value,
        onClose = onClose,
        onSettingsSectionClick = onSettingsSectionClick,
        onDeveloperToolsClick = onDeveloperToolsClick,
        sectionContent = sectionContent,
        developerContent = developerContent,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(
    state: SettingsHomeContract.UiState,
    onClose: () -> Unit,
    onSettingsSectionClick: (PrimalSettingsSection) -> Unit,
    onDeveloperToolsClick: () -> Unit,
    sectionContent: (@Composable (PrimalSettingsSection) -> Unit)?,
    developerContent: (@Composable () -> Unit)?,
    eventPublisher: (SettingsHomeContract.UiEvent) -> Unit,
) {
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = expandedSection != null) {
        expandedSection = null
    }

    PrimalScaffold(
        modifier = Modifier,
        topBar = {
            PrimalTopAppBar(
                title = stringResource(id = R.string.settings_title),
                navigationIcon = PrimalIcons.ArrowBack,
                navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                onNavigationIconClick = onClose,
            )
        },
        content = { paddingValues ->
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    items(
                        items = PrimalSettingsSection.entries.filter {
                            it != PrimalSettingsSection.Wallet &&
                                it != PrimalSettingsSection.ConnectedApps &&
                                it != PrimalSettingsSection.Account &&
                                it != PrimalSettingsSection.Zaps
                        },
                        key = { it.name },
                    ) {
                        SettingsListItem(
                            title = it.title(),
                            leadingIcon = it.icon(),
                            onClick = {
                                if (sectionContent == null) {
                                    onSettingsSectionClick(it)
                                } else {
                                    expandedSection = if (expandedSection == it.name) null else it.name
                                }
                            },
                            trailingIcon = if (expandedSection == it.name) {
                                Icons.Outlined.KeyboardArrowDown
                            } else {
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight
                            },
                            walletNeedsBackup = it == PrimalSettingsSection.Wallet && state.walletNeedsBackup,
                        )
                        AnimatedVisibility(
                            visible = expandedSection == it.name,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 640.dp),
                            ) {
                                sectionContent?.invoke(it)
                            }
                        }
                        PrimalDivider()
                    }

                    if (state.developerToolsEnabled) {
                        item(key = "developer_tools") {
                            SettingsListItem(
                                title = stringResource(id = R.string.settings_developer_tools_title),
                                leadingIcon = PrimalIcons.Key,
                                onClick = {
                                    if (developerContent == null) {
                                        onDeveloperToolsClick()
                                    } else {
                                        expandedSection = if (expandedSection == "developer_tools") null else "developer_tools"
                                    }
                                },
                                trailingIcon = if (expandedSection == "developer_tools") {
                                    Icons.Outlined.KeyboardArrowDown
                                } else {
                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                                },
                            )
                            AnimatedVisibility(
                                visible = expandedSection == "developer_tools",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 80.dp, max = 640.dp),
                                ) {
                                    developerContent?.invoke()
                                }
                            }
                            PrimalDivider()
                        }
                    }
                }

                VersionBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 18.dp, bottom = 12.dp),
                    versionName = state.version,
                    onClick = { eventPublisher(SettingsHomeContract.UiEvent.VersionTapped) },
                )
            }
        },
    )
}

@Composable
private fun SettingsListItem(
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    walletNeedsBackup: Boolean = false,
) {
    ListItem(
        modifier = Modifier
            .height(64.dp)
            .clickable { onClick() },
        colors = ListItemDefaults.colors(
            containerColor = AppTheme.colorScheme.background,
        ),
        leadingContent = if (leadingIcon != null) {
            {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = AppTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = AppTheme.typography.titleMedium,
                )
                if (walletNeedsBackup) {
                    Badge(
                        modifier = Modifier
                            .size(size = 8.dp)
                            .offset(x = 8.dp, y = (-8).dp),
                        containerColor = AppTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            if (description != null) {
                Text(
                    text = description,
                )
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (walletNeedsBackup) {
                    Text(
                        text = stringResource(id = R.string.settings_wallet_needs_backup),
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                    )
                }
                if (trailingIcon != null) {
                    Icon(imageVector = trailingIcon, contentDescription = null)
                }
            }
        },
    )
}

@Composable
private fun VersionBadge(modifier: Modifier, versionName: String, onClick: () -> Unit) {
    Text(
        modifier = modifier.clickable(onClick = onClick),
        text = "ver. $versionName",
        style = AppTheme.typography.labelSmall,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
    )
}

private fun PrimalSettingsSection.icon(): ImageVector {
    return when (this) {
        PrimalSettingsSection.Account -> PrimalIcons.MenuAccount
        PrimalSettingsSection.Wallet -> PrimalIcons.NavWallet
        PrimalSettingsSection.Network -> PrimalIcons.ConnectRelay
        PrimalSettingsSection.Appearance -> PrimalIcons.DarkMode
        PrimalSettingsSection.ConnectedApps -> PrimalIcons.NostrConnectSession
        PrimalSettingsSection.ContentDisplay -> PrimalIcons.FontSize
        PrimalSettingsSection.MutedAccounts -> PrimalIcons.MuteUser
        PrimalSettingsSection.MediaUploads -> PrimalIcons.ImportPhotoFromGallery
        PrimalSettingsSection.Notifications -> PrimalIcons.Notifications
        PrimalSettingsSection.Zaps -> PrimalIcons.Zap
        PrimalSettingsSection.Language -> Icons.Outlined.Language
    }
}

@Composable
private fun PrimalSettingsSection.title(): String {
    return when (this) {
        PrimalSettingsSection.Account -> stringResource(id = R.string.settings_account_title)
        PrimalSettingsSection.Network -> stringResource(id = R.string.settings_network_title)
        PrimalSettingsSection.Wallet -> stringResource(id = R.string.settings_wallet_title)
        PrimalSettingsSection.Appearance -> stringResource(id = R.string.settings_appearance_title)
        PrimalSettingsSection.ContentDisplay -> stringResource(id = R.string.settings_content_display_title)
        PrimalSettingsSection.Notifications -> stringResource(id = R.string.settings_notifications_title)
        PrimalSettingsSection.Zaps -> stringResource(id = R.string.settings_zaps_title)
        PrimalSettingsSection.MutedAccounts -> stringResource(id = R.string.settings_muted_content_title)
        PrimalSettingsSection.MediaUploads -> stringResource(id = R.string.settings_media_uploads_title)
        PrimalSettingsSection.ConnectedApps -> stringResource(id = R.string.settings_connected_apps_title)
        PrimalSettingsSection.Language -> stringResource(id = R.string.settings_language_title)
    }
}

@Preview
@Composable
private fun PreviewSettingsHomeScreen() {
    PrimalPreview(primalTheme = PrimalTheme.Ice) {
        SettingsHomeScreen(
            state = SettingsHomeContract.UiState(version = "1.1"),
            onClose = { },
            onSettingsSectionClick = {},
            onDeveloperToolsClick = {},
            sectionContent = null,
            developerContent = null,
            eventPublisher = {},
        )
    }
}
