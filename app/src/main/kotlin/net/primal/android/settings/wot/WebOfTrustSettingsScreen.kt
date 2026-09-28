package net.primal.android.settings.wot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalSwitch
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.icons.primaliconpack.MuteUser
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.compose.settings.SettingsItem
import net.primal.android.theme.AppTheme
import net.primal.domain.wot.WotDiscoveryState

@Composable
fun WebOfTrustSettingsScreen(
    viewModel: WebOfTrustSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val uiState = viewModel.uiState.collectAsState()
    WebOfTrustSettingsScreen(
        state = uiState.value,
        onClose = onClose,
        embedded = embedded,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebOfTrustSettingsScreen(
    state: WebOfTrustSettingsContract.UiState,
    onClose: () -> Unit,
    embedded: Boolean = false,
    eventPublisher: (WebOfTrustSettingsContract.UiEvent) -> Unit,
) {
    if (embedded) {
        WebOfTrustSettingsContent(state = state, eventPublisher = eventPublisher)
    } else {
        PrimalScaffold(
            modifier = Modifier,
            topBar = {
                PrimalTopAppBar(
                    title = stringResource(id = R.string.settings_wot_title),
                    navigationIcon = PrimalIcons.ArrowBack,
                    navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                    onNavigationIconClick = onClose,
                )
            },
            content = { paddingValues ->
                WebOfTrustSettingsContent(
                    modifier = Modifier.padding(paddingValues),
                    state = state,
                    eventPublisher = eventPublisher,
                )
            },
        )
    }
}

@Composable
private fun WebOfTrustSettingsContent(
    state: WebOfTrustSettingsContract.UiState,
    eventPublisher: (WebOfTrustSettingsContract.UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(color = AppTheme.colorScheme.surfaceVariant)
            .fillMaxWidth(),
    ) {
        SettingsItem(
            headlineText = stringResource(id = R.string.settings_wot_enable_title),
            supportText = stringResource(id = R.string.settings_wot_enable_description),
            leadingIcon = PrimalIcons.MuteUser,
            trailingContent = {
                PrimalSwitch(
                    checked = state.filterEnabled,
                    onCheckedChange = { eventPublisher(WebOfTrustSettingsContract.UiEvent.ToggleFilter(enabled = it)) },
                )
            },
            onClick = {
                eventPublisher(WebOfTrustSettingsContract.UiEvent.ToggleFilter(enabled = !state.filterEnabled))
            },
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = state.discoveryState.statusText(),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                modifier = Modifier.padding(top = 8.dp),
                enabled = state.discoveryState !is WotDiscoveryState.Discovering,
                onClick = { eventPublisher(WebOfTrustSettingsContract.UiEvent.RefreshNetworkClick) },
            ) {
                Text(text = stringResource(id = R.string.settings_wot_refresh_button))
            }
        }
    }
}

@Composable
private fun WotDiscoveryState.statusText(): String =
    when (this) {
        WotDiscoveryState.Idle -> stringResource(id = R.string.settings_wot_status_idle)
        is WotDiscoveryState.Discovering ->
            stringResource(id = R.string.settings_wot_status_discovering, fetchedFollowLists, totalFollows)
        is WotDiscoveryState.Complete ->
            pluralStringResource(
                id = R.plurals.settings_wot_status_complete,
                count = qualifiedCount,
                firstDegreeCount,
                qualifiedCount,
            )
        is WotDiscoveryState.Failed -> stringResource(id = R.string.settings_wot_status_failed, reason)
    }

@Composable
@androidx.compose.ui.tooling.preview.Preview
private fun PreviewWebOfTrustSettingsScreen() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        WebOfTrustSettingsScreen(
            state = WebOfTrustSettingsContract.UiState(
                filterEnabled = true,
                discoveryState = WotDiscoveryState.Complete(
                    firstDegreeCount = 420,
                    qualifiedCount = 1_337,
                    computedAtSeconds = 0,
                ),
            ),
            onClose = {},
            eventPublisher = {},
        )
    }
}
