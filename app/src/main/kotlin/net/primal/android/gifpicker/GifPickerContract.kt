package net.primal.android.gifpicker

import net.primal.android.core.errors.UiError
import net.primal.android.gifpicker.domain.GifItem
import net.primal.data.remote.api.gifs.model.GifSource

interface GifPickerContract {

    /**
     * @property source the provider the visible results came from, for the attribution line;
     *   null until the first page has loaded.
     * @property topics one-tap queries shown as chips under the search field: Nostr staples while
     *   nothing is typed, the provider's autocomplete for the current text otherwise.
     */
    data class UiState(
        val searchQuery: String = "",
        val gifItems: List<GifItem> = emptyList(),
        val searching: Boolean = false,
        val source: GifSource? = null,
        val topics: List<String> = DEFAULT_TOPICS,
        val error: UiError? = null,
    )

    sealed class UiEvent {
        /**
         * The picker became visible. The first page loads on this rather than when the view model
         * is created: the composer creates its picker's view model up front, and fetching a page
         * of GIFs every time anyone opens the composer would spend data on a picker most posts
         * never open.
         */
        data object PickerShown : UiEvent()
        data class UpdateSearchQuery(val query: String) : UiEvent()
        data class SelectGif(val gif: GifItem) : UiEvent()
        data object LoadMoreGifs : UiEvent()
        data object DismissError : UiEvent()
    }

    sealed class SideEffect {
        data class GifSelected(val gif: GifItem) : SideEffect()
    }

    data class ScreenCallbacks(
        val onClose: () -> Unit,
        val onGifSelected: (GifItem) -> Unit,
    )

    companion object {
        /** The default chips — the same Nostr reaction staples zap.observer's picker opens with. */
        val DEFAULT_TOPICS = listOf("gm", "lol", "yes", "wow", "applause", "facepalm", "love", "fire", "bitcoin")
    }
}
