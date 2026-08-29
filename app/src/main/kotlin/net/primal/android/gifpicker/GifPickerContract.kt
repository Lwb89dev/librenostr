package net.primal.android.gifpicker

import net.primal.android.core.errors.UiError
import net.primal.android.gifpicker.domain.GifItem

interface GifPickerContract {

    data class UiState(
        val searchQuery: String = "",
        val gifItems: List<GifItem> = emptyList(),
        val searching: Boolean = false,
        val error: UiError? = null,
    )

    sealed class UiEvent {
        data class UpdateSearchQuery(val query: String) : UiEvent()
        data class SelectGif(val gif: GifItem) : UiEvent()
        data object LoadMoreGifs : UiEvent()
        data object DismissError : UiEvent()
    }

    sealed class SideEffect {
        data class GifSelected(val url: String) : SideEffect()
    }

    data class ScreenCallbacks(
        val onClose: () -> Unit,
        val onGifSelected: (String) -> Unit,
    )
}
