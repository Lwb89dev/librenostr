@file:Suppress("MagicNumber")

package net.primal.android.auth.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush
import net.primal.android.theme.AppTheme

/** Explains the current built-in Tor choices without making onboarding a configuration wizard. */
@Composable
fun OrbotOnboardingScreen(onComplete: () -> Unit) {
    ColumnWithBackground(
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(id = R.string.onboarding_orbot_title),
                style = AppTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = PrimalDarkTextColor,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                modifier = Modifier.widthIn(max = 440.dp),
                text = stringResource(id = R.string.onboarding_orbot_description),
                style = AppTheme.typography.bodyLarge,
                color = PrimalDarkTextColor.copy(alpha = 0.76f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(26.dp))
            Column(
                modifier = Modifier.widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrivacyModeCard(
                    index = "01",
                    title = stringResource(id = R.string.settings_tor_mode_direct_title),
                    description = stringResource(id = R.string.settings_tor_mode_direct_description),
                )
                PrivacyModeCard(
                    index = "02",
                    title = stringResource(id = R.string.settings_tor_mode_tor_title),
                    description = stringResource(id = R.string.settings_tor_mode_tor_description),
                    emphasized = true,
                )
                PrivacyModeCard(
                    index = "03",
                    title = stringResource(id = R.string.settings_tor_mode_onion_only_title),
                    description = stringResource(id = R.string.settings_tor_mode_onion_only_description),
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF6B3FE8).copy(alpha = 0.12f),
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        text = stringResource(id = R.string.onboarding_orbot_restart_notice),
                        style = AppTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = PrimalDarkTextColor.copy(alpha = 0.82f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
            OnboardingButton(
                text = stringResource(id = R.string.onboarding_orbot_continue),
                onClick = onComplete,
            )
        }
    }
}

@Composable
private fun PrivacyModeCard(
    index: String,
    title: String,
    description: String,
    emphasized: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = if (emphasized) 0.26f else 0.16f),
        border = BorderStroke(
            width = 1.dp,
            color = if (emphasized) Color(0xFF6B3FE8).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = if (emphasized) Color(0xFF6B3FE8) else Color.White.copy(alpha = 0.3f),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = index,
                        style = AppTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (emphasized) Color.White else PrimalDarkTextColor,
                    )
                }
            }
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = PrimalDarkTextColor,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = AppTheme.typography.bodySmall,
                    color = PrimalDarkTextColor.copy(alpha = 0.68f),
                )
            }
        }
    }
}
