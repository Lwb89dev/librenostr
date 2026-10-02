package net.primal.android.editor

import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import java.util.*
import net.primal.android.articles.feed.ui.FeedArticleUi
import net.primal.android.articles.highlights.HighlightUi
import net.primal.android.core.errors.UiError
import net.primal.android.drawer.multiaccount.model.UserAccountUi
import net.primal.android.editor.domain.NoteAttachment
import net.primal.android.editor.domain.NoteTaggedUser
import net.primal.android.gifpicker.domain.GifItem
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.model.PollType
import net.primal.android.profile.mention.UserTaggingState
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.Nevent

interface NoteEditorContract {

    data class PollChoice(
        val id: UUID = UUID.randomUUID(),
        val text: String = "",
    )

    data class PollEditorState(
        val choices: List<PollChoice> = listOf(PollChoice(), PollChoice()),
        val pollType: PollType = PollType.User,
        val pollLengthDays: Int = 1,
        val pollLengthHours: Int = 0,
        val pollLengthMinutes: Int = 0,
        val minZapAmountInSats: Long? = 21L,
        val maxZapAmountInSats: Long? = 21_000L,
    )

    data class UiState(
        /** Seconds left before the note goes out, or null when nothing is pending. */
        val undoCountdownSeconds: Int? = null,
        /** The configured total, so the countdown ring knows what fraction is left. */
        val undoPostTimerSeconds: Int = 0,
        val content: TextFieldValue = TextFieldValue(),
        val replyToConversation: List<FeedPostUi> = emptyList(),
        val replyToArticle: FeedArticleUi? = null,
        val replyToHighlight: HighlightUi? = null,
        val isQuoting: Boolean = false,
        val publishing: Boolean = false,
        val error: UiError? = null,
        val selectedAccount: UserAccountUi? = null,
        val uploadingAttachments: Boolean = false,
        val attachments: List<NoteAttachment> = emptyList(),
        val taggedUsers: List<NoteTaggedUser> = emptyList(),
        val referencedNostrUris: List<ReferencedUri<*>> = emptyList(),
        val userTaggingState: UserTaggingState = UserTaggingState(),
        val availableAccounts: List<UserAccountUi> = emptyList(),
        val attachedGifs: List<AttachedGif> = emptyList(),
        val pollState: PollEditorState? = null,
        val isPrivateReply: Boolean = false,
        val privateReplyRecipientId: String? = null,
        val privateReplyRecipientName: String? = null,
        val privateReplyRecipientPickerVisible: Boolean = false,
        val canSendPrivateReply: Boolean = false,
        /**
         * Every NIP-30 custom emoji the account can use, shortcode to URL: what a `:shortcode:` in
         * the note resolves to when it is published, and what the publish preview draws.
         */
        val availableCustomEmojis: Map<String, String> = emptyMap(),
    ) {
        val isReply: Boolean get() = replyToConversation.isNotEmpty()
        val replyToNote: FeedPostUi? = replyToConversation.lastOrNull()
    }

    sealed class UiEvent {
        data class UpdateContent(val content: TextFieldValue) : UiEvent()
        data class PasteContent(val content: TextFieldValue) : UiEvent()
        data class RefreshUri(val uri: String) : UiEvent()
        data class RemoveUri(val uriIndex: Int) : UiEvent()
        data class RemoveHighlightByArticle(val articleATag: String) : UiEvent()
        data object AppendUserTagAtSign : UiEvent()
        data object PublishNote : UiEvent()
        data object CancelScheduledPublish : UiEvent()
        data object ConfirmScheduledPublish : UiEvent()
        data class ImportLocalFiles(val uris: List<Uri>) : UiEvent()
        data class DiscardNoteAttachment(val attachmentId: UUID) : UiEvent()
        data class RetryUpload(val attachmentId: UUID) : UiEvent()
        data class SearchUsers(val query: String) : UiEvent()
        data class ToggleSearchUsers(val enabled: Boolean) : UiEvent()
        data class TagUser(val taggedUser: NoteTaggedUser) : UiEvent()
        data class SelectAccount(val accountId: String) : UiEvent()
        data class InsertGif(val gif: GifItem) : UiEvent()
        data class RemoveGif(val gifId: UUID) : UiEvent()
        data object DismissError : UiEvent()
        data object TogglePollMode : UiEvent()
        data class UpdatePollChoice(val choiceId: UUID, val text: String) : UiEvent()
        data object AddPollChoice : UiEvent()
        data class RemovePollChoice(val choiceId: UUID) : UiEvent()
        data class UpdatePollLength(val days: Int, val hours: Int, val minutes: Int) : UiEvent()
        data class UpdatePollType(val pollType: PollType) : UiEvent()
        data class UpdateMinZapAmount(val amountInSats: Long?) : UiEvent()
        data class UpdateMaxZapAmount(val amountInSats: Long?) : UiEvent()
        data object ShowPrivateReplyRecipientPicker : UiEvent()
        data object HidePrivateReplyRecipientPicker : UiEvent()
        data class SelectPrivateReplyRecipient(val userId: String, val userName: String) : UiEvent()
        data object ClearPrivateReplyRecipient : UiEvent()
    }

    sealed class SideEffect {
        data object PostPublished : SideEffect()
    }

    sealed interface ReferencedUri<T> {
        val loading: Boolean
        val uri: String
        val data: T?

        data class Note(
            override val data: FeedPostUi?,
            override val loading: Boolean,
            override val uri: String,
            val nevent: Nevent,
        ) : ReferencedUri<FeedPostUi>

        data class Article(
            override val data: FeedArticleUi?,
            override val loading: Boolean,
            override val uri: String,
            val naddr: Naddr,
        ) : ReferencedUri<FeedArticleUi>

        data class Highlight(
            override val data: HighlightUi?,
            override val loading: Boolean,
            override val uri: String,
            val nevent: Nevent,
        ) : ReferencedUri<HighlightUi>

        data class LightningInvoice(
            override val loading: Boolean,
            override val uri: String,
            override val data: String,
        ) : ReferencedUri<String>
    }

    /**
     * A GIF picked for this note. It is posted by its provider URL, never downloaded and uploaded
     * again: the picker's GIFs already live on public media hosts (nostr.build's above all), so
     * there is nothing to wait for and nothing that can fail between picking and publishing.
     */
    data class AttachedGif(
        val id: UUID = UUID.randomUUID(),
        val gif: GifItem,
    )

    data class ScreenCallbacks(
        val onClose: () -> Unit,
        val onGifPickerClick: () -> Unit = {},
        val onManageEmojiPacks: () -> Unit = {},
    )
}
