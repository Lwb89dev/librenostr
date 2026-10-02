package net.primal.android.emoji.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.primal.android.emoji.BuiltInEmojiPacks
import net.primal.android.emoji.model.EmojiPack
import net.primal.android.emoji.repository.CustomEmojiRepository
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching

/**
 * Feeds the composer's emoji panel. The packs come straight from [CustomEmojiRepository]'s cached
 * library, so the panel opens with what the account had last time; [onPickerShown] then refreshes
 * it from relays in the background if it is more than a few minutes old.
 */
@HiltViewModel
class EmojiPickerViewModel @Inject constructor(
    private val customEmojiRepository: CustomEmojiRepository,
) : ViewModel() {

    val packs: StateFlow<List<EmojiPack>> = customEmojiRepository.library
        .map { it.enabledPacks }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = BuiltInEmojiPacks.all,
        )

    fun onPickerShown() {
        viewModelScope.launch {
            runCatching { customEmojiRepository.ensureFresh() }
                .onFailure { Napier.w(it) { "Could not refresh the emoji library" } }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
