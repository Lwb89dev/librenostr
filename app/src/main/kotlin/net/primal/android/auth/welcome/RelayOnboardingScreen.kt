@file:Suppress("MagicNumber")

package net.primal.android.auth.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush
import net.primal.android.networking.relays.ONBOARDING_RELAY_OPTIONS
import net.primal.android.networking.relays.RelayNote
import net.primal.android.theme.AppTheme

@Composable
fun RelayOnboardingScreen(
    viewModel: RelayOnboardingViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    RelayOnboardingScreen(
        state = state,
        onAddRelay = viewModel::addRelay,
        onToggleRelay = viewModel::toggleRelay,
        onContinue = { viewModel.save(onComplete) },
    )
}

@Composable
private fun RelayOnboardingScreen(
    state: RelayOnboardingState,
    onAddRelay: (String) -> Unit,
    onToggleRelay: (String) -> Unit,
    onContinue: () -> Unit,
) {
    ColumnWithBackground(
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RelayOnboardingHeader()
            Spacer(modifier = Modifier.height(22.dp))
            if (state.loading) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Color(0xFF6B3FE8))
                }
            } else {
                RelaySelectionContent(
                    modifier = Modifier.weight(1f),
                    state = state,
                    onAddRelay = onAddRelay,
                    onToggleRelay = onToggleRelay,
                )
                Spacer(modifier = Modifier.height(18.dp))
                OnboardingButton(
                    text = if (state.saving) {
                        stringResource(id = R.string.onboarding_relay_saving)
                    } else {
                        stringResource(id = R.string.onboarding_relay_continue)
                    },
                    enabled = state.selected.size >= MINIMUM_RELAY_COUNT && !state.saving,
                    loading = state.saving,
                    onClick = onContinue,
                )
            }
        }
    }
}

@Composable
private fun RelayOnboardingHeader() {
    Text(
        text = stringResource(id = R.string.onboarding_relay_eyebrow),
        style = AppTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        ),
        color = PrimalDarkTextColor.copy(alpha = 0.58f),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(id = R.string.onboarding_relay_title),
        style = AppTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
        color = PrimalDarkTextColor,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = stringResource(id = R.string.onboarding_relay_description),
        style = AppTheme.typography.bodyLarge,
        color = PrimalDarkTextColor.copy(alpha = 0.76f),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RelaySelectionContent(
    modifier: Modifier = Modifier,
    state: RelayOnboardingState,
    onAddRelay: (String) -> Unit,
    onToggleRelay: (String) -> Unit,
) {
    var customRelay by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth()) {
        CustomRelayField(
            value = customRelay,
            onValueChange = { customRelay = it },
            onAdd = {
                onAddRelay(customRelay)
                customRelay = ""
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(id = R.string.onboarding_relay_hint),
                style = AppTheme.typography.bodySmall,
                color = PrimalDarkTextColor.copy(alpha = 0.65f),
            )
            Text(
                text = stringResource(
                    id = R.string.onboarding_relay_count,
                    state.selected.size,
                    state.suggestions.size,
                ),
                style = AppTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = PrimalDarkTextColor,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.suggestions, key = { it }) { relay ->
                RelayOption(
                    relay = relay,
                    selected = relay in state.selected,
                    onClick = { onToggleRelay(relay) },
                )
            }
        }
    }
}

@Composable
private fun CustomRelayField(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focus ->
                    if (focus.isFocused && value.isEmpty()) onValueChange(RELAY_URL_SCHEME)
                },
            value = value,
            onValueChange = { typed ->
                onValueChange(
                    if (typed.startsWith(RELAY_URL_SCHEME)) {
                        typed
                    } else {
                        RELAY_URL_SCHEME + typed.removePrefix(RELAY_URL_SCHEME.take(typed.length))
                    },
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text(stringResource(id = R.string.onboarding_relay_custom_placeholder)) },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            enabled = value.length > RELAY_URL_SCHEME.length,
            onClick = onAdd,
        ) {
            Text(text = stringResource(id = R.string.onboarding_relay_add))
        }
    }
}

@Composable
private fun RelayOption(
    relay: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = if (selected) 0.26f else 0.14f),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) Color(0xFF6B3FE8).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.26f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.removePrefix(RELAY_URL_SCHEME),
                    style = AppTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = PrimalDarkTextColor,
                )
                relayNoteStringRes(relay)?.let { noteStringRes ->
                    Text(
                        text = stringResource(id = noteStringRes),
                        style = AppTheme.typography.labelMedium,
                        color = PrimalDarkTextColor.copy(alpha = 0.62f),
                    )
                }
            }
        }
    }
}

private fun relayNoteStringRes(relay: String): Int? =
    when (ONBOARDING_RELAY_OPTIONS.find { it.url == relay }?.note) {
        RelayNote.Paid -> R.string.onboarding_relay_note_paid
        RelayNote.ProfilesOnly -> R.string.onboarding_relay_note_profiles
        else -> null
    }

private const val MINIMUM_RELAY_COUNT = 3
private const val RELAY_URL_SCHEME = "wss://"
