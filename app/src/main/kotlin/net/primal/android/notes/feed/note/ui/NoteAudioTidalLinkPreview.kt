package net.primal.android.notes.feed.note.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import net.primal.android.theme.AppTheme
import net.primal.domain.links.EventUriType

private val TIDAL_HOSTS = setOf("listen.tidal.com", "tidal.com", "embed.tidal.com")
private val TIDAL_ID = Regex("^[a-zA-Z0-9_-]+$")

private fun String.convertToTidalEmbedUrl(): String? {
    val uri = android.net.Uri.parse(this)
    val host = uri.host?.lowercase() ?: return null
    if (host !in TIDAL_HOSTS) return null
    val segments = uri.pathSegments.filter { it.isNotBlank() }
    if (segments.size < 2) return null
    val type = if (segments[0] == "playlist") "playlists" else segments[0]
    val id = segments[1]
    if (!TIDAL_ID.matches(id)) return null
    return "https://embed.tidal.com/$type/$id"
}

@Composable
fun NoteAudioTidalLinkPreview(
    modifier: Modifier = Modifier,
    url: String,
    title: String?,
    description: String?,
    thumbnailUrl: String?,
) {
    val density = LocalDensity.current
    val embedUrl = url.convertToTidalEmbedUrl()

    Box(modifier = modifier) {
        var embeddedWebState by remember { mutableStateOf(EmbeddedWebPageState.Idle) }
        var previewSize by remember { mutableStateOf(DpSize(width = 0.dp, height = 0.dp)) }

        val readyToEmbed = embeddedWebState == EmbeddedWebPageState.Ready ||
            embeddedWebState == EmbeddedWebPageState.Initializing
        if (embedUrl != null && readyToEmbed) {
            NoteEmbeddedWebPagePreview(
                modifier = Modifier
                    .clip(AppTheme.shapes.medium)
                    .size(size = previewSize),
                url = embedUrl,
                state = embeddedWebState,
                onPageLoaded = { embeddedWebState = EmbeddedWebPageState.Ready },
                pageLoadedReadyDelayMillis = 200.milliseconds.inWholeMilliseconds,
                domStorageEnabled = true,
            )
        }

        if (embeddedWebState == EmbeddedWebPageState.Idle || embeddedWebState == EmbeddedWebPageState.Initializing) {
            NoteAudioLinkPreview(
                modifier = Modifier.onSizeChanged {
                    with(density) {
                        previewSize = DpSize(width = it.width.toDp(), height = it.height.toDp())
                    }
                },
                title = title,
                description = description,
                thumbnailUrl = thumbnailUrl,
                eventUriType = EventUriType.Tidal,
                loading = embeddedWebState == EmbeddedWebPageState.Initializing,
                onPlayClick = { embeddedWebState = EmbeddedWebPageState.Initializing },
            )
        }
    }
}
