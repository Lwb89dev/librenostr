@file:Suppress("MagicNumber")

package net.primal.android.auth.welcome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
fun WelcomeScreen(callbacks: WelcomeContract.ScreenCallbacks) {
    ColumnWithBackground(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        backgroundBrushProvider = ::primalGradientBrush,
        brushAlpha = PrimalGradientAlpha,
        backgroundColor = PrimalGradientBackgroundColor,
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            WelcomeAmbientShapes()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WelcomeIcon()
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(id = R.string.onboarding_welcome_eyebrow),
                    style = AppTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                    ),
                    color = PrimalDarkTextColor.copy(alpha = 0.62f),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.onboarding_welcome_title),
                    style = AppTheme.typography.headlineLarge.copy(
                        fontFamily = LibreSansFontFamily,
                        fontWeight = FontWeight.Black,
                        fontSize = 42.sp,
                    ),
                    color = PrimalDarkTextColor,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    modifier = Modifier.widthIn(max = 440.dp),
                    text = stringResource(id = R.string.onboarding_welcome_tagline),
                    style = AppTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = PrimalDarkTextColor,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    modifier = Modifier.widthIn(max = 420.dp),
                    text = stringResource(id = R.string.onboarding_welcome_description),
                    style = AppTheme.typography.bodyLarge,
                    color = PrimalDarkTextColor.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(28.dp))
                WelcomeFeatureCard()
                Spacer(modifier = Modifier.height(30.dp))
                OnboardingButton(
                    modifier = Modifier.testTag(OnboardingTestTags.WELCOME_SIGN_IN_BUTTON),
                    text = stringResource(id = R.string.welcome_sign_in_button_title),
                    onClick = callbacks.onSignInClick,
                )
            }
        }
    }
}

@Composable
private fun WelcomeIcon() {
    val transition = rememberInfiniteTransition(label = "WelcomeIconPulse")
    val scale by transition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "WelcomeIconScale",
    )
    Surface(
        modifier = Modifier
            .size(150.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(44.dp),
        color = Color.White.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.38f)),
        shadowElevation = 18.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                modifier = Modifier.size(108.dp),
                painter = painterResource(id = R.drawable.primal_icon),
                contentDescription = stringResource(id = R.string.app_name),
            )
        }
    }
}

@Composable
private fun WelcomeFeatureCard() {
    Surface(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WelcomeFeature(text = stringResource(id = R.string.onboarding_welcome_feature_relays))
            WelcomeFeature(text = stringResource(id = R.string.onboarding_welcome_feature_tor))
            WelcomeFeature(text = stringResource(id = R.string.onboarding_welcome_feature_local))
        }
    }
}

@Composable
private fun WelcomeFeature(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color(0xFF6B3FE8), CircleShape),
        )
        Spacer(modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = AppTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = PrimalDarkTextColor,
        )
    }
}

@Composable
private fun BoxScope.WelcomeAmbientShapes() {
    Box(
        modifier = Modifier
            .offset(x = (-54).dp, y = 90.dp)
            .size(150.dp)
            .background(Color.White.copy(alpha = 0.08f), CircleShape),
    )
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 62.dp, y = (-42).dp)
            .size(190.dp)
            .background(Color(0xFF6B3FE8).copy(alpha = 0.08f), CircleShape),
    )
}

@Preview
@Composable
fun PreviewWelcomeScreen() {
    PrimalTheme(primalTheme = PrimalTheme.Midnight) {
        WelcomeScreen(callbacks = WelcomeContract.ScreenCallbacks(onSignInClick = {}))
    }
}
