package net.primal.android.auth.onboarding.account.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalDarkTextColor
import net.primal.android.theme.AppTheme

private const val TOTAL_ONBOARDING_STEPS = 4
private const val FULLY_ROUNDED_CORNER_PERCENT = 50

@Composable
fun OnboardingStepsIndicator(currentPage: Int) {
    val safeCurrentPage = currentPage.coerceIn(0, TOTAL_ONBOARDING_STEPS - 1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                id = R.string.onboarding_step_progress,
                safeCurrentPage + 1,
                TOTAL_ONBOARDING_STEPS,
            ),
            style = AppTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = PrimalDarkTextColor.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(TOTAL_ONBOARDING_STEPS) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(
                            color = if (index <= safeCurrentPage) {
                                PrimalDarkTextColor
                            } else {
                                PrimalDarkTextColor.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(FULLY_ROUNDED_CORNER_PERCENT),
                        ),
                )
            }
        }
    }
}
