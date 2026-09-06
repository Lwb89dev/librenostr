package net.primal.android.core.compose.fab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.theme.AppTheme

@Composable
fun NewPostFloatingActionButton(onNewPostClick: () -> Unit) {
    val tokens = AppTheme.libreNostrTokens
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(listOf(tokens.accent, tokens.accentSecondary)),
                shape = CircleShape,
            )
            .clickable(onClick = onNewPostClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(25.dp),
            imageVector = Icons.Outlined.Add,
            contentDescription = stringResource(id = R.string.accessibility_new_post),
            tint = AppTheme.colorScheme.onPrimary,
        )
    }
}
