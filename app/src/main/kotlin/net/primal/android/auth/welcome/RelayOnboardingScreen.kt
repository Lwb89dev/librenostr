package net.primal.android.auth.welcome

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush
import androidx.compose.ui.focus.onFocusChanged
import net.primal.android.networking.relays.ONBOARDING_RELAY_OPTIONS
import net.primal.android.networking.relays.RelayNote
import androidx.compose.ui.res.stringResource
import net.primal.android.R
import net.primal.android.theme.AppTheme

@Composable
fun RelayOnboardingScreen(
    viewModel: RelayOnboardingViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    ColumnWithBackground(
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Connect to Nostr",
                style = AppTheme.typography.headlineMedium,
                color = PrimalDarkTextColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Choose a few relays. LibreNostr connects directly to them; no caching server is involved.",
                style = AppTheme.typography.bodyLarge,
                color = PrimalDarkTextColor.copy(alpha = 0.86f),
            )
            Spacer(Modifier.height(18.dp))
            if (state.loading) {
                CircularProgressIndicator(color = AppTheme.colorScheme.primary)
            } else {
                var customRelay by remember { mutableStateOf("") }
                Text(
                    text = "Your NIP-65 relay list is used when available. Start with at least three.",
                    style = AppTheme.typography.bodyMedium,
                    color = PrimalDarkTextColor.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            // Every relay address starts the same way, so the scheme is filled in
                            // on focus and the user only types the host.
                            .onFocusChanged { focus ->
                                if (focus.isFocused && customRelay.isEmpty()) {
                                    customRelay = RELAY_URL_SCHEME
                                }
                            },
                        value = customRelay,
                        onValueChange = { typed ->
                            // Keep the scheme pinned: deleting into it would otherwise leave a
                            // half-scheme that never validates.
                            customRelay = if (typed.startsWith(RELAY_URL_SCHEME)) {
                                typed
                            } else {
                                RELAY_URL_SCHEME + typed.removePrefix(RELAY_URL_SCHEME.take(typed.length))
                            }
                        },
                        singleLine = true,
                        placeholder = { Text("wss://your-relay.example") },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = customRelay.length > RELAY_URL_SCHEME.length,
                        onClick = {
                            viewModel.addRelay(customRelay)
                            customRelay = ""
                        },
                    ) {
                        Text("Add")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${state.selected.size} of ${state.suggestions.size} selected",
                    style = AppTheme.typography.labelMedium,
                    color = PrimalDarkTextColor.copy(alpha = 0.72f),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.suggestions, key = { it }) { relay ->
                        val note = ONBOARDING_RELAY_OPTIONS.find { it.url == relay }?.note
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = relay in state.selected,
                                onCheckedChange = { viewModel.toggleRelay(relay) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = relay.removePrefix(RELAY_URL_SCHEME),
                                    color = PrimalDarkTextColor,
                                    style = AppTheme.typography.bodyLarge,
                                )
                                // What the probe found, said up front rather than discovered
                                // after picking it.
                                val caption = when (note) {
                                    RelayNote.Paid -> stringResource(id = R.string.onboarding_relay_note_paid)
                                    RelayNote.ProfilesOnly ->
                                        stringResource(id = R.string.onboarding_relay_note_profiles)
                                    else -> null
                                }
                                if (caption != null) {
                                    Text(
                                        text = caption,
                                        color = PrimalDarkTextColor.copy(alpha = 0.62f),
                                        style = AppTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.selected.size >= 3 && !state.saving,
                    onClick = { viewModel.save(onComplete) },
                ) {
                    Text(if (state.saving) "Saving…" else "Continue")
                }
            }
        }
    }
}

private const val RELAY_URL_SCHEME = "wss://"
