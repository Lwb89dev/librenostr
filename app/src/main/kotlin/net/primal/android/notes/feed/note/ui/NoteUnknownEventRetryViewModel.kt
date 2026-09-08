package net.primal.android.notes.feed.note.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.launch
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.posts.FeedRepository

/**
 * Retries a single "Mentioned event not found" reference from the feed/thread render path.
 *
 * Scoped to the screen (the default `hiltViewModel()` lifecycle) rather than per note card, so
 * every unresolved reference on the same screen shares one instance. The fetch persists to Room
 * on success, and the card that reads from a live query re-renders on its own — there is no
 * local state to update here, unlike the note editor's in-memory retry.
 */
@HiltViewModel
class NoteUnknownEventRetryViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    fun retry(uri: String) {
        val noteId = uri.extractNoteId() ?: return
        viewModelScope.launch {
            try {
                feedRepository.fetchConversation(
                    userId = activeAccountStore.activeUserId(),
                    noteId = noteId,
                    limit = 1,
                )
            } catch (error: NetworkException) {
                Napier.w(error) { "Failed to retry unknown event uri=$uri" }
            }
        }
    }
}
