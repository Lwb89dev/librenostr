package net.primal.android.events.gallery

import net.primal.android.core.compose.attachment.model.EventUriUi
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.domain.links.EventUriType

interface EventMediaGalleryContract {
    data class UiState(
        val noteId: String,
        val loading: Boolean = true,
        val initialAttachmentIndex: Int = 0,
        val initialPositionMs: Long = 0,
        val error: MediaGalleryError? = null,
        val attachments: List<EventUriUi> = emptyList(),
        /** Null until the note this gallery was opened from has been fetched, or if it could
         * not be found (e.g. deleted). The reply/zap/like/repost bar only renders once set. */
        val note: FeedPostUi? = null,
    ) {
        sealed class MediaGalleryError {
            data class FailedToSaveMedia(val cause: Throwable) : MediaGalleryError()
        }
    }

    sealed class UiEvent {
        data class SaveMedia(val attachment: EventUriUi) : UiEvent()
        data object DismissError : UiEvent()
    }

    sealed class SideEffect {
        data class MediaSaved(val type: EventUriType) : SideEffect()
    }
}
