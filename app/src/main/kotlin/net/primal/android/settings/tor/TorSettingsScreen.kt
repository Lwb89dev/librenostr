package net.primal.android.settings.tor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.primal.android.R
import net.primal.android.core.compose.PrimalDefaults
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalSwitch
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.SnackbarErrorHandler
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.icons.primaliconpack.Key
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.compose.settings.SettingsItem
import net.primal.android.theme.AppTheme

@Composable
fun TorSettingsScreen(
    viewModel: TorSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val uiState = viewModel.uiState.collectAsState()
    TorSettingsScreen(
        state = uiState.value,
        onClose = onClose,
        embedded = embedded,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorSettingsScreen(
    state: TorSettingsContract.UiState,
    onClose: () -> Unit,
    embedded: Boolean = false,
    eventPublisher: (TorSettingsContract.UiEvent) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    SnackbarErrorHandler(
        error = if (state.showRestartNotice) Unit else null,
        snackbarHostState = snackbarHostState,
        errorMessageResolver = { stringResource(id = R.string.settings_tor_restart_required) },
        onErrorDismiss = { eventPublisher(TorSettingsContract.UiEvent.DismissRestartNotice) },
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                eventPublisher(TorSettingsContract.UiEvent.RecheckOrbotInstalled)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PrimalScaffold(
        modifier = Modifier,
        topBar = if (embedded) {
            null
        } else {
            {
            PrimalTopAppBar(
                title = stringResource(id = R.string.settings_tor_title),
                navigationIcon = PrimalIcons.ArrowBack,
                navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                onNavigationIconClick = onClose,
            )
            }
        },
        content = { paddingValues ->
            TorSettingsContent(
                modifier = Modifier.padding(paddingValues),
                state = state,
                eventPublisher = eventPublisher,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    )
}

@Composable
private fun TorSettingsContent(
    state: TorSettingsContract.UiState,
    eventPublisher: (TorSettingsContract.UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(color = AppTheme.colorScheme.surfaceVariant)
            .fillMaxWidth(),
    ) {
        SettingsItem(
            headlineText = stringResource(id = R.string.settings_tor_enable_title),
            supportText = stringResource(id = R.string.settings_tor_enable_description),
            leadingIcon = PrimalIcons.Key,
            trailingContent = {
                PrimalSwitch(
                    checked = state.torEnabled,
                    onCheckedChange = {
                        eventPublisher(TorSettingsContract.UiEvent.ToggleTor(enabled = it))
                    },
                )
            },
            onClick = {
                eventPublisher(TorSettingsContract.UiEvent.ToggleTor(enabled = !state.torEnabled))
            },
        )

        if (state.torEnabled) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = PrimalDefaults.outlinedTextFieldColors(),
                shape = AppTheme.shapes.medium,
                value = state.torSocksPortText,
                onValueChange = {
                    eventPublisher(TorSettingsContract.UiEvent.UpdateTorPort(rawPort = it))
                },
                singleLine = true,
                isError = state.torPortInvalid,
                label = { Text(text = stringResource(id = R.string.settings_tor_port_title)) },
                supportingText = if (state.torPortInvalid) {
                    { Text(text = stringResource(id = R.string.settings_tor_port_error)) }
                } else {
                    null
                },
                textStyle = AppTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            if (!state.orbotInstalled) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = stringResource(id = R.string.settings_tor_orbot_not_installed),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colorScheme.error,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewTorSettingsScreen() {
    PrimalPreview(primalTheme = net.primal.android.theme.domain.PrimalTheme.Midnight) {
        TorSettingsScreen(
            state = TorSettingsContract.UiState(torEnabled = true, orbotInstalled = false),
            onClose = {},
            eventPublisher = {},
        )
    }
}
