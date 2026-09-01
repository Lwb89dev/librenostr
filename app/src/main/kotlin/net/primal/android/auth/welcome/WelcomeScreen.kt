package net.primal.android.auth.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.auth.OnboardingTestTags
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush
import net.primal.android.theme.AppTheme
import net.primal.android.theme.LibreSansFontFamily
import net.primal.android.theme.PrimalTheme
import net.primal.android.theme.domain.PrimalTheme

@Composable
@Suppress("MagicNumber")
fun WelcomeScreen(callbacks: WelcomeContract.ScreenCallbacks) {
    ColumnWithBackground(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        // Mark and name at the top, what the app actually is at the bottom, with the space
        // between them left empty rather than filled.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth(0.55f),
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = stringResource(id = R.string.app_name),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(id = R.string.onboarding_welcome_title),
                style = AppTheme.typography.headlineMedium.copy(
                    fontFamily = LibreSansFontFamily,
                    fontWeight = FontWeight.Light,
                    fontStyle = FontStyle.Italic,
                    fontSize = 36.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = PrimalDarkTextColor,
            )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                modifier = Modifier.widthIn(max = 420.dp),
                text = stringResource(id = R.string.onboarding_welcome_description),
                style = AppTheme.typography.bodyLarge,
                color = PrimalDarkTextColor.copy(alpha = 0.86f),
            )
            Spacer(modifier = Modifier.height(36.dp))
            OnboardingButton(
                modifier = Modifier.testTag(OnboardingTestTags.WELCOME_SIGN_IN_BUTTON),
                text = stringResource(id = R.string.welcome_sign_in_button_title),
                onClick = callbacks.onSignInClick,
            )
            }
        }
    }
}

@Preview
@Composable
fun PreviewWelcomeScreen() {
    PrimalTheme(
        primalTheme = PrimalTheme.Midnight,
    ) {
        WelcomeScreen(
            callbacks = WelcomeContract.ScreenCallbacks(
                onSignInClick = {},
            ),
        )
    }
}
