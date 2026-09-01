package net.primal.android.editor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.theme.AppTheme

/**
 * The last moment a note can be called off.
 *
 * Publishing to Nostr is effectively final: relays keep what they accept, a NIP-09 deletion is a
 * request rather than a guarantee, and anyone who already fetched the note keeps their copy. These
 * few seconds are the only recall the protocol offers, so the screen is deliberately in the way
 * rather than a small snackbar that is easy to miss.
 */
@Composable
fun PublishCountdownOverlay(
    secondsRemaining: Int,
    totalSeconds: Int,
    hasUploadedAttachments: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (totalSeconds > 0) secondsRemaining.toFloat() / totalSeconds else 0f,
        animationSpec = tween(durationMillis = 1_000),
        label = "publishCountdown",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(120.dp),
                    progress = { progress },
                    strokeWidth = 4.dp,
                    color = AppTheme.colorScheme.primary,
                    trackColor = AppTheme.extraColorScheme.surfaceVariantAlt1,
                )
                Text(
                    text = secondsRemaining.toString(),
                    fontSize = 44.sp,
                    color = AppTheme.colorScheme.onSurface,
                    style = AppTheme.typography.displaySmall,
                )
            }

            Text(
                text = pluralStringResource(R.plurals.editor_publish_countdown_title, secondsRemaining),
                textAlign = TextAlign.Center,
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onSurface,
            )

            if (hasUploadedAttachments) {
                // Said plainly rather than left to be discovered: the timer holds back the note,
                // not the file. Attachments are uploaded when they are picked.
                Text(
                    text = stringResource(id = R.string.editor_publish_countdown_attachments_notice),
                    textAlign = TextAlign.Center,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
                )
            }

            PrimalLoadingButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(id = R.string.editor_publish_countdown_cancel),
                onClick = onCancel,
            )
        }
    }
}
