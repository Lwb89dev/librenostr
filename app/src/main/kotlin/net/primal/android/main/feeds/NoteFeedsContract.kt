package net.primal.android.main.feeds

import net.primal.android.core.errors.UiError
import net.primal.android.feeds.list.ui.model.FeedUi

interface NoteFeedsContract {

    data class UiState(
        val feeds: List<FeedUi> = emptyList(),
        val loading: Boolean = true,
        val uiError: UiError? = null,
    )

    sealed class UiEvent {
        data object RefreshNoteFeeds : UiEvent()
        data object RestoreDefaultNoteFeeds : UiEvent()
        data object DismissError : UiEvent()
    }
}
