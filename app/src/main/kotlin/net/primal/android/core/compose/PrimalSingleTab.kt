package net.primal.android.core.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.primal.android.theme.AppTheme

@Composable
fun PrimalSingleTab(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    textStyle: TextStyle = AppTheme.typography.bodySmall,
) {
    Tab(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        selectedContentColor = Color.Unspecified,
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            icon?.let {
                Icon(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(18.dp),
                    imageVector = it,
                    contentDescription = null,
                    tint = AppTheme.colorScheme.primary,
                )
            }
            Text(
                modifier = Modifier
                    .padding(vertical = if (icon == null) 16.dp else 8.dp)
                    .fillMaxWidth(),
                text = text,
                textAlign = TextAlign.Center,
                style = textStyle,
                color = AppTheme.colorScheme.onPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
        }
    }
}
