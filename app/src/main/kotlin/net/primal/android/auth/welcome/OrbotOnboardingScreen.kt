package net.primal.android.auth.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush
import net.primal.android.core.ext.openUriSafely
import net.primal.android.theme.AppTheme
import net.primal.android.theme.LibreSansFontFamily

private const val ORBOT_ZAPSTORE_URL = "https://zapstore.dev/apps/org.torproject.android"

/**
 * Optional privacy step, shown once at the end of onboarding for both new and existing
 * accounts. Purely informational — Tor stays off until the user turns it on later in
 * Settings > Tor, so there is nothing to skip here, only to acknowledge.
 */
@Composable
fun OrbotOnboardingScreen(onComplete: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    ColumnWithBackground(
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(id = R.string.onboarding_orbot_title),
                style = AppTheme.typography.headlineMedium.copy(
                    fontFamily = LibreSansFontFamily,
                    fontWeight = FontWeight.Light,
                    fontStyle = FontStyle.Italic,
                    fontSize = 30.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = PrimalDarkTextColor,
            )

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    modifier = Modifier.widthIn(max = 420.dp),
                    text = stringResource(id = R.string.onboarding_orbot_description),
                    style = AppTheme.typography.bodyLarge,
                    color = PrimalDarkTextColor.copy(alpha = 0.86f),
                )
                Text(
                    modifier = Modifier.widthIn(max = 420.dp),
                    text = stringResource(id = R.string.onboarding_orbot_restart_notice),
                    style = AppTheme.typography.bodyMedium,
                    color = PrimalDarkTextColor.copy(alpha = 0.7f),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { uriHandler.openUriSafely(ORBOT_ZAPSTORE_URL) }) {
                    Text(
                        text = stringResource(id = R.string.onboarding_orbot_install_button),
                        style = AppTheme.typography.bodyLarge,
                        color = PrimalDarkTextColor,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OnboardingButton(
                    text = stringResource(id = R.string.onboarding_orbot_continue),
                    onClick = onComplete,
                )
            }
        }
    }
}
