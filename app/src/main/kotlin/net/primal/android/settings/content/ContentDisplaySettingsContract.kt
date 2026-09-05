package net.primal.android.settings.content

import net.primal.android.user.domain.ContentDisplaySettings

interface ContentDisplaySettingsContract {

    data class UiState(
        val autoPlayVideos: Int = ContentDisplaySettings.AUTO_PLAY_VIDEO_NEVER,
        val showAnimatedAvatars: Boolean = false,
        val showLiveStreams: Boolean = false,
        val autoUpdateFeed: Boolean = true,
        val undoPostTimerEnabled: Boolean = true,
        val undoPostTimerSeconds: Int = ContentDisplaySettings.DEFAULT_UNDO_POST_SECONDS,
        val undoPostTimerForReplies: Boolean = false,
        val translateNotesEnabled: Boolean = false,
        val translateServerUrl: String = "",
    )

    sealed class UiEvent {
        data class UpdateAutoPlayVideos(val code: Int) : UiEvent()
        data class UpdateShowAnimatedAvatars(val enabled: Boolean) : UiEvent()
        data class UpdateShowLiveStreams(val enabled: Boolean) : UiEvent()
        data class UpdateAutoUpdateFeed(val enabled: Boolean) : UiEvent()
        data class UpdateUndoPostTimerEnabled(val enabled: Boolean) : UiEvent()
        data class UpdateUndoPostTimerSeconds(val seconds: Int) : UiEvent()
        data class UpdateUndoPostTimerForReplies(val enabled: Boolean) : UiEvent()
        data class UpdateTranslateNotesEnabled(val enabled: Boolean) : UiEvent()
        data class UpdateTranslateServerUrl(val url: String) : UiEvent()
    }
}
