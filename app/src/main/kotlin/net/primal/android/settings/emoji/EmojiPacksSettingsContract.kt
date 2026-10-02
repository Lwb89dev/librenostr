package net.primal.android.settings.emoji

import android.net.Uri
import net.primal.android.core.errors.UiError
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.EmojiPack

interface EmojiPacksSettingsContract {

    /**
     * @property enabledPacks the packs the composer offers: built in first, then the account's
     *   emoji list in its own order.
     * @property ownPacks packs the account authored — the ones it can edit and delete.
     * @property discoveredPacks recent packs from the account's relays, to browse and add.
     * @property busyAddresses packs with an add or remove still publishing, to disable their buttons.
     * @property editor the pack being created or edited, or null on the list.
     */
    data class UiState(
        val userId: String = "",
        val refreshing: Boolean = false,
        val enabledPacks: List<EmojiPack> = emptyList(),
        val ownPacks: List<EmojiPack> = emptyList(),
        val enabledAddresses: Set<String> = emptySet(),
        val discoveredPacks: List<EmojiPack> = emptyList(),
        val discovering: Boolean = false,
        val busyAddresses: Set<String> = emptySet(),
        val linkInput: String = "",
        val addingByLink: Boolean = false,
        val editor: PackEditorState? = null,
        val error: UiError? = null,
    )

    /**
     * A pack being made or edited. Each emoji is added by picking an image, which is uploaded to
     * the account's Blossom servers right away under [pendingShortcode]; nothing is published until
     * [saving] the whole pack.
     *
     * @property identifier the pack's `d` tag, null for a pack that does not exist yet.
     */
    data class PackEditorState(
        val identifier: String? = null,
        val title: String = "",
        val emojis: List<CustomEmoji> = emptyList(),
        val pendingShortcode: String = "",
        val uploading: Boolean = false,
        val saving: Boolean = false,
    ) {
        val canSave: Boolean get() = title.isNotBlank() && emojis.isNotEmpty() && !uploading && !saving
    }

    sealed class UiEvent {
        data object Discover : UiEvent()
        data class AddPack(val pack: EmojiPack) : UiEvent()
        data class RemovePack(val address: String) : UiEvent()
        data class UpdateLinkInput(val text: String) : UiEvent()
        data object AddPackFromLink : UiEvent()

        data object CreatePack : UiEvent()
        data class EditPack(val pack: EmojiPack) : UiEvent()
        data class DeletePack(val pack: EmojiPack) : UiEvent()
        data object CloseEditor : UiEvent()
        data class UpdateEditorTitle(val title: String) : UiEvent()
        data class UpdatePendingShortcode(val shortcode: String) : UiEvent()
        data class AddEmojiImage(val uri: Uri) : UiEvent()
        data class RemoveEditorEmoji(val shortcode: String) : UiEvent()
        data object SaveEditor : UiEvent()

        data object DismissError : UiEvent()
    }
}
