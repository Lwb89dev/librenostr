package net.primal.android.core.compose.signer

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import net.primal.android.R
import net.primal.android.core.compose.AppIconThumbnail
import net.primal.android.core.compose.NostrUserText
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.UniversalAvatarThumbnail
import net.primal.android.core.compose.button.PrimalFilledButton
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.HighSecurity
import net.primal.android.core.compose.icons.primaliconpack.LowSecurity
import net.primal.android.core.compose.icons.primaliconpack.MediumSecurity
import net.primal.android.core.compose.nostrconnect.PermissionsListItem
import net.primal.android.core.ext.selectableItem
import net.primal.android.core.utils.formatNip05Identifier
import net.primal.android.drawer.multiaccount.model.UserAccountUi
import net.primal.android.navigation.primalSlideInHorizontallyFromEnd
import net.primal.android.navigation.primalSlideOutHorizontallyToEnd
import net.primal.android.theme.AppTheme
import net.primal.domain.account.model.TrustLevel

private enum class SignerConnectTab {
    Login,
    Permissions,
}

@Composable
fun SignerConnectBottomSheet(
    appName: String?,
    appDescription: String?,
    accounts: List<UserAccountUi>,
    connecting: Boolean,
    onConnectClick: (UserAccountUi, TrustLevel) -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
    appImageUrl: String? = null,
    appIcon: Drawable? = null,
) {
    var selectedTab by remember { mutableStateOf(SignerConnectTab.Login) }
    var trustLevel by remember { mutableStateOf(TrustLevel.Medium) }
    var selectedAccount by remember(accounts) { mutableStateOf(accounts.firstOrNull()) }

    BackHandler {
        onCancelClick()
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.extraColorScheme.surfaceVariantAlt2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeaderSection(
                appIconUrl = appImageUrl,
                appIcon = appIcon,
                appName = appName,
                appDescription = appDescription,
            )

            SignerConnectTabNavigation(
                selectedTab = selectedTab,
                onTabChange = { selectedTab = it },
                permissionsTabEnabled = accounts.isNotEmpty(),
            )

            SignerConnectPages(
                modifier = Modifier
                    .height(400.dp)
                    .weight(1f, fill = false),
                selectedTab = selectedTab,
                accounts = accounts,
                selectedAccount = selectedAccount,
                trustLevel = trustLevel,
                onAccountSelect = { pubkey -> selectedAccount = accounts.find { it.pubkey == pubkey } },
                onTrustLevelSelect = { trustLevel = it },
            )

            SignerConnectFooter(
                connecting = connecting,
                primaryButtonEnabled = selectedAccount != null,
                onConnect = {
                    selectedAccount?.let {
                        onConnectClick(it, trustLevel)
                    }
                },
                onCancel = onCancelClick,
            )
        }
    }
}

@Composable
private fun SignerConnectFooter(
    connecting: Boolean,
    primaryButtonEnabled: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    ActionButtons(
        primaryButtonEnabled = primaryButtonEnabled,
        primaryButtonLoading = connecting,
        primaryButtonText = stringResource(id = R.string.signer_connect_connect_button),
        onPrimaryClick = onConnect,
        secondaryButtonText = stringResource(id = R.string.signer_connect_cancel_button),
        onSecondaryClick = onCancel,
    )
}

@Composable
private fun HeaderSection(
    appName: String?,
    appIconUrl: String?,
    appIcon: Drawable?,
    appDescription: String?,
) {
    Column(
        modifier = Modifier.padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (appIcon != null) {
            Image(
                modifier = Modifier
                    .size(48.dp)
                    .clip(AppTheme.shapes.small),
                bitmap = appIcon.toBitmap().asImageBitmap(),
                contentDescription = appName,
                contentScale = ContentScale.Crop,
            )
        } else {
            AppIconThumbnail(
                appIconUrl = appIconUrl,
                avatarSize = 48.dp,
                appName = appName,
            )
        }

        Text(
            text = appName ?: stringResource(id = R.string.signer_connect_unknown_app),
            style = AppTheme.typography.titleLarge.copy(
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = AppTheme.colorScheme.onPrimary,
        )

        appDescription?.let { description ->
            Text(
                text = description,
                style = AppTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                ),
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignerConnectTabNavigation(
    selectedTab: SignerConnectTab,
    onTabChange: (SignerConnectTab) -> Unit,
    permissionsTabEnabled: Boolean,
) {
    val selectedTabIndex = selectedTab.ordinal
    PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = Color.Transparent,
        contentColor = AppTheme.colorScheme.onSurface,
        indicator = {
            Box(
                modifier = Modifier
                    .tabIndicatorOffset(selectedTabIndex)
                    .height(6.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 2.dp)
                    .clip(CircleShape)
                    .background(color = AppTheme.colorScheme.primary),
            )
        },
        divider = {
            PrimalDivider()
        },
    ) {
        SignerConnectAppTab(
            text = stringResource(id = R.string.signer_connect_login_tab).uppercase(),
            selected = selectedTab == SignerConnectTab.Login,
            onClick = { onTabChange(SignerConnectTab.Login) },
        )

        SignerConnectAppTab(
            text = stringResource(id = R.string.signer_connect_permissions_tab).uppercase(),
            selected = selectedTab == SignerConnectTab.Permissions,
            onClick = { onTabChange(SignerConnectTab.Permissions) },
            enabled = permissionsTabEnabled,
        )
    }
}

@Composable
private fun SignerConnectAppTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Tab(
        modifier = modifier.padding(horizontal = 16.dp),
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        text = {
            Text(
                text = text,
                style = AppTheme.typography.bodySmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 14.sp,
                ),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) {
                    AppTheme.colorScheme.onPrimary
                } else {
                    AppTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        },
    )
}

@Composable
private fun SignerConnectPages(
    modifier: Modifier = Modifier,
    selectedTab: SignerConnectTab,
    accounts: List<UserAccountUi>,
    selectedAccount: UserAccountUi?,
    trustLevel: TrustLevel,
    onAccountSelect: (String) -> Unit,
    onTrustLevelSelect: (TrustLevel) -> Unit,
) {
    AnimatedContent(
        modifier = modifier.background(color = AppTheme.extraColorScheme.surfaceVariantAlt3),
        targetState = selectedTab,
        transitionSpec = {
            val slideIn = if (targetState.ordinal > initialState.ordinal) {
                primalSlideInHorizontallyFromEnd
            } else {
                slideInHorizontally { -it }
            }
            val slideOut = if (targetState.ordinal > initialState.ordinal) {
                primalSlideOutHorizontallyToEnd
            } else {
                slideOutHorizontally { it }
            }
            slideIn.togetherWith(slideOut)
        },
        label = "TabAnimation",
    ) { tab ->
        when (tab) {
            SignerConnectTab.Login -> LoginContent(
                accounts = accounts,
                selectedAccountPubkey = selectedAccount?.pubkey,
                onAccountClick = onAccountSelect,
            )

            SignerConnectTab.Permissions -> PermissionsContent(
                trustLevel = trustLevel,
                onTrustLevelClick = onTrustLevelSelect,
            )
        }
    }
}

@Composable
private fun LoginContent(
    accounts: List<UserAccountUi>,
    selectedAccountPubkey: String?,
    onAccountClick: (String) -> Unit,
) {
    if (accounts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(id = R.string.signer_connect_no_nsec_accounts_warning),
                textAlign = TextAlign.Center,
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(accounts, key = { it.pubkey }) { account ->
                AccountListItem(
                    account = account,
                    isSelected = account.pubkey == selectedAccountPubkey,
                    onClick = { onAccountClick(account.pubkey) },
                )
            }
        }
    }
}

@Composable
private fun AccountListItem(
    account: UserAccountUi,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.selectableItem(
            selected = isSelected,
            onClick = onClick,
        ),
        colors = ListItemDefaults.colors(containerColor = AppTheme.extraColorScheme.surfaceVariantAlt1),
        leadingContent = {
            UniversalAvatarThumbnail(
                avatarSize = 40.dp,
                avatarCdnImage = account.avatarCdnImage,
                avatarBlossoms = account.avatarBlossoms,
            )
        },
        headlineContent = {
            NostrUserText(
                displayName = account.displayName,
                internetIdentifier = account.internetIdentifier,
                displayNameColor = AppTheme.colorScheme.onSurface,
                profileId = account.pubkey,
            )
        },
        supportingContent = account.internetIdentifier?.let {
            {
                Text(
                    text = it.formatNip05Identifier(),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                )
            }
        },
    )
}

@Composable
private fun PermissionsContent(
    trustLevel: TrustLevel,
    onTrustLevelClick: (TrustLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionsListItem(
            icon = PrimalIcons.HighSecurity,
            title = stringResource(id = R.string.signer_connect_full_trust_title),
            subtitle = stringResource(id = R.string.signer_connect_full_trust_subtitle),
            isSelected = trustLevel == TrustLevel.Full,
            onClick = { onTrustLevelClick(TrustLevel.Full) },
        )
        PermissionsListItem(
            icon = PrimalIcons.MediumSecurity,
            title = stringResource(id = R.string.signer_connect_medium_trust_title),
            subtitle = stringResource(id = R.string.signer_connect_medium_trust_subtitle),
            isSelected = trustLevel == TrustLevel.Medium,
            onClick = { onTrustLevelClick(TrustLevel.Medium) },
        )
        PermissionsListItem(
            icon = PrimalIcons.LowSecurity,
            title = stringResource(id = R.string.signer_connect_low_trust_title),
            subtitle = stringResource(id = R.string.signer_connect_low_trust_subtitle),
            isSelected = trustLevel == TrustLevel.Low,
            onClick = { onTrustLevelClick(TrustLevel.Low) },
        )
    }
}

@Composable
private fun ActionButtons(
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String,
    onSecondaryClick: () -> Unit,
    primaryButtonLoading: Boolean = false,
    primaryButtonEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = AppTheme.extraColorScheme.surfaceVariantAlt3,
            )
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimalFilledButton(
            modifier = Modifier.weight(1f),
            height = 50.dp,
            containerColor = AppTheme.colorScheme.outline,
            contentColor = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
            border = BorderStroke(width = 1.dp, color = AppTheme.colorScheme.outline),
            textStyle = AppTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            onClick = onSecondaryClick,
        ) {
            Text(text = secondaryButtonText)
        }

        PrimalLoadingButton(
            modifier = Modifier.weight(1f),
            height = 50.dp,
            loading = primaryButtonLoading,
            enabled = primaryButtonEnabled,
            text = primaryButtonText,
            onClick = onPrimaryClick,
        )
    }
}

