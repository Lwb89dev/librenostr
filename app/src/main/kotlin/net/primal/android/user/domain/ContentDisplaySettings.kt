package net.primal.android.user.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContentDisplaySettings(
    val autoPlayVideos: Int = AUTO_PLAY_VIDEO_NEVER,
    val autoPlayVideoSoundOn: Boolean = true,
    val showAnimatedAvatars: Boolean = false,
    val showLiveStreams: Boolean = false,
    /** Refresh the active feed when the app/feed becomes visible again. */
    val autoUpdateFeed: Boolean = true,
    /** Hold a note back for a few seconds after tapping post, so it can still be called off. */
    val undoPostTimerEnabled: Boolean = true,
    val undoPostTimerSeconds: Int = DEFAULT_UNDO_POST_SECONDS,
    /** Replies are usually short and deliberate, so they go out immediately unless asked. */
    val undoPostTimerForReplies: Boolean = false,
    @SerialName("noteAppearance") val contentAppearance: ContentAppearance = ContentAppearance.Default,
    /** Offers a translate action on notes, calling a user-configured LibreTranslate-compatible server. */
    val translateNotesEnabled: Boolean = false,
    val translateServerUrl: String = "",
) {
    companion object {
        const val AUTO_PLAY_VIDEO_NEVER = 0
        const val AUTO_PLAY_VIDEO_ALWAYS = 1

        const val MIN_UNDO_POST_SECONDS = 1
        const val MAX_UNDO_POST_SECONDS = 7
        const val DEFAULT_UNDO_POST_SECONDS = 5
    }
}
