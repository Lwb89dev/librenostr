package net.primal.android.settings.emoji

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.core.errors.UiError
import net.primal.android.emoji.Nip30
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.EmojiPack
import net.primal.android.emoji.repository.CustomEmojiRepository
import net.primal.android.emoji.repository.EmojiLibrary
import net.primal.android.emoji.repository.EmojiLibraryUnavailableException
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.PackEditorState
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.UiEvent
import net.primal.android.settings.emoji.EmojiPacksSettingsContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.networking.blossom.AndroidPrimalBlossomUploadService
import net.primal.core.networking.blossom.UploadResult
import net.primal.domain.nostr.utils.takeAsNaddrOrNull

@HiltViewModel
class EmojiPacksSettingsViewModel @Inject constructor(
    private val customEmojiRepository: CustomEmojiRepository,
    private val activeAccountStore: ActiveAccountStore,
    private val uploadService: AndroidPrimalBlossomUploadService,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private fun updateEditor(reducer: PackEditorState.() -> PackEditorState) =
        setState { copy(editor = editor?.reducer()) }

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeLibrary()
        observeEvents()
        refreshLibrary()
        discoverPacks()
    }

    private fun observeLibrary() =
        viewModelScope.launch {
            customEmojiRepository.library.collect { library -> setState { withLibrary(library) } }
        }

    @Suppress("CyclomaticComplexMethod")
    private fun observeEvents() =
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    UiEvent.Discover -> discoverPacks()
                    is UiEvent.AddPack -> addPack(event.pack)
                    is UiEvent.RemovePack -> removePack(event.address)
                    is UiEvent.UpdateLinkInput -> setState { copy(linkInput = event.text) }
                    UiEvent.AddPackFromLink -> addPackFromLink()
                    UiEvent.CreatePack -> setState { copy(editor = PackEditorState()) }
                    is UiEvent.EditPack -> setState { copy(editor = event.pack.asEditorState()) }
                    is UiEvent.DeletePack -> deletePack(event.pack)
                    UiEvent.CloseEditor -> setState { copy(editor = null) }
                    is UiEvent.UpdateEditorTitle -> updateEditor { copy(title = event.title) }
                    is UiEvent.UpdatePendingShortcode -> updateEditor {
                        copy(pendingShortcode = event.shortcode.trim())
                    }
                    is UiEvent.AddEmojiImage -> uploadEmoji(event.uri)
                    is UiEvent.RemoveEditorEmoji -> updateEditor {
                        copy(emojis = emojis.filterNot { it.shortcode == event.shortcode })
                    }
                    UiEvent.SaveEditor -> saveEditor()
                    UiEvent.DismissError -> setState { copy(error = null) }
                }
            }
        }

    private fun refreshLibrary() =
        viewModelScope.launch {
            setState { copy(refreshing = true) }
            runLibraryAction { customEmojiRepository.refresh() }
            setState { copy(refreshing = false) }
        }

    private fun discoverPacks() =
        viewModelScope.launch {
            setState { copy(discovering = true) }
            val packs = runLibraryAction { customEmojiRepository.discoverPacks() }.orEmpty()
            setState { copy(discovering = false, discoveredPacks = packs) }
        }

    private fun addPack(pack: EmojiPack) =
        whileBusy(pack.address) { customEmojiRepository.addPackToList(pack) }

    private fun removePack(address: String) =
        whileBusy(address) { customEmojiRepository.removePackFromList(address) }

    private fun deletePack(pack: EmojiPack) =
        whileBusy(pack.address) {
            customEmojiRepository.deletePack(pack)
            setState { copy(editor = null) }
        }

    /** Accepts an `naddr` (bare or as a `nostr:` URI), fetches the pack and adds it. */
    private fun addPackFromLink() =
        viewModelScope.launch {
            val naddr = _state.value.linkInput.trim().takeAsNaddrOrNull()
            if (naddr == null) {
                setState { copy(error = UiError.InvalidNaddr) }
                return@launch
            }
            setState { copy(addingByLink = true) }
            val pack = runLibraryAction { customEmojiRepository.fetchPack(naddr) }
            if (pack == null) {
                setState { copy(addingByLink = false, error = UiError.InvalidNaddr) }
                return@launch
            }
            runLibraryAction { customEmojiRepository.addPackToList(pack) }
            setState { copy(addingByLink = false, linkInput = "") }
        }

    /**
     * Uploads one picked image to the account's Blossom servers and adds it to the pack being
     * edited under the shortcode typed for it, or a generated one when none was typed.
     */
    private fun uploadEmoji(uri: Uri) =
        viewModelScope.launch {
            val editor = _state.value.editor ?: return@launch
            val shortcode = editor.nextShortcode() ?: return@launch
            updateEditor { copy(uploading = true) }
            val result = uploadService.upload(uri = uri, userId = activeAccountStore.activeUserId())
            when (result) {
                is UploadResult.Success -> updateEditor {
                    copy(
                        uploading = false,
                        pendingShortcode = "",
                        emojis = emojis + CustomEmoji(shortcode = shortcode, url = result.remoteUrl),
                    )
                }
                is UploadResult.Failed -> {
                    Napier.w(result.error) { "Emoji upload failed" }
                    updateEditor { copy(uploading = false) }
                    setState { copy(error = UiError.FailedToUploadAttachment(result.error)) }
                }
            }
        }

    private fun saveEditor() =
        viewModelScope.launch {
            val editor = _state.value.editor?.takeIf { it.canSave } ?: return@launch
            updateEditor { copy(saving = true) }
            val saved = runLibraryAction {
                customEmojiRepository.savePack(
                    identifier = editor.identifier,
                    title = editor.title.trim(),
                    emojis = editor.emojis,
                )
            }
            setState { if (saved != null) copy(editor = null) else copy(editor = this.editor?.copy(saving = false)) }
        }

    private fun whileBusy(address: String, action: suspend () -> Unit) =
        viewModelScope.launch {
            setState { copy(busyAddresses = busyAddresses + address) }
            runLibraryAction(action)
            setState { copy(busyAddresses = busyAddresses - address) }
        }

    /** Runs a library call, turning its failure into the error the screen shows; null on failure. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runLibraryAction(action: suspend () -> T): T? =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: EmojiLibraryUnavailableException) {
            setState { copy(error = UiError.NetworkError(error)) }
            null
        } catch (error: Exception) {
            Napier.w(error) { "Emoji library action failed" }
            setState { copy(error = UiError.PublishError(error)) }
            null
        }

    private fun UiState.withLibrary(library: EmojiLibrary): UiState =
        copy(
            userId = library.userId,
            enabledPacks = library.enabledPacks,
            ownPacks = library.ownPacks,
            enabledAddresses = library.userList?.packAddresses.orEmpty().toSet(),
        )

    private fun EmojiPack.asEditorState() = PackEditorState(identifier = identifier, title = title, emojis = emojis)

    /**
     * The shortcode for the next emoji: the one typed, if valid and free; a numbered default when
     * nothing was typed; null (no upload) when what was typed cannot be used — the field already
     * shows why.
     */
    private fun PackEditorState.nextShortcode(): String? {
        val taken = emojis.map { it.shortcode }.toSet()
        if (pendingShortcode.isNotEmpty()) {
            return pendingShortcode.takeIf { Nip30.isValidShortcode(it) && it !in taken }
        }
        return generateSequence(emojis.size + 1) { it + 1 }.map { "emoji$it" }.first { it !in taken }
    }
}
