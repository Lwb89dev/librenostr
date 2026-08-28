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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.auth.OnboardingTestTags
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush
import net.primal.android.theme.PrimalTheme
import net.primal.android.theme.domain.PrimalTheme

@Composable
fun WelcomeScreen(callbacks: WelcomeContract.ScreenCallbacks) {
    ColumnWithBackground(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth(0.55f),
                painter = painterResource(id = R.drawable.primal_icon),
                contentDescription = "LibreNostr",
            )

            Spacer(modifier = Modifier.height(40.dp))

            OnboardingButton(
                modifier = Modifier.testTag(OnboardingTestTags.WELCOME_SIGN_IN_BUTTON),
                text = stringResource(id = R.string.welcome_sign_in_button_title),
                onClick = callbacks.onSignInClick,
            )
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
