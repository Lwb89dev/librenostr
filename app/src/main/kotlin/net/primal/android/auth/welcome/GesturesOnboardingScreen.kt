package net.primal.android.auth.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.core.compose.ColumnWithBackground
import net.primal.android.theme.AppTheme
import net.primal.android.theme.LibreSansFontFamily
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.core.compose.PrimalGradientAlpha
import net.primal.android.core.compose.PrimalGradientBackgroundColor
import net.primal.android.core.compose.primalGradientBrush

/**
 * The last onboarding step: the two gestures that are otherwise undiscoverable.
 *
 * Both drawers open from the middle of Home and nothing on screen hints at them, so a user who is
 * never told simply does not find them.
 */
@Composable
fun GesturesOnboardingScreen(onComplete: () -> Unit) {
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
                text = stringResource(id = R.string.onboarding_gestures_title),
                style = AppTheme.typography.headlineMedium.copy(
                    fontFamily = LibreSansFontFamily,
                    fontWeight = FontWeight.Light,
                    fontStyle = FontStyle.Italic,
                    fontSize = 30.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = PrimalDarkTextColor,
            )

            Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                GestureHint(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    text = stringResource(id = R.string.onboarding_gestures_algorithms),
                )
                GestureHint(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    text = stringResource(id = R.string.onboarding_gestures_reads),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                OnboardingButton(
                    text = stringResource(id = R.string.onboarding_gestures_continue),
                    onClick = onComplete,
                )
            }
        }
    }
}

@Composable
private fun GestureHint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            modifier = Modifier.size(32.dp),
            imageVector = icon,
            contentDescription = null,
            tint = PrimalDarkTextColor,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            modifier = Modifier.widthIn(max = 420.dp),
            text = text,
            style = AppTheme.typography.bodyLarge,
            color = PrimalDarkTextColor.copy(alpha = 0.86f),
        )
    }
}
