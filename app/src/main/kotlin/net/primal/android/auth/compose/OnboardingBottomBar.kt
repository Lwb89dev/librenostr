package net.primal.android.auth.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingBottomBar(
    buttonText: String,
    onButtonClick: () -> Unit,
    buttonEnabled: Boolean = true,
    buttonLoading: Boolean = false,
    buttonTestTag: String? = null,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingButton(
                text = buttonText,
                modifier = Modifier
                    .then(if (buttonTestTag != null) Modifier.testTag(buttonTestTag) else Modifier)
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                onClick = {
                    keyboardController?.hide()
                    onButtonClick()
                },
                enabled = buttonEnabled,
                loading = buttonLoading,
            )

            footer()
        }
    }
}
