package net.primal.android.notes.feed.note.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.primal.android.notes.feed.model.NoteNostrUriUi
import net.primal.android.notes.feed.model.asNoteNostrUriUi
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.posts.FeedRepository

/**
 * Retries a single "Mentioned event not found" reference from the feed/thread render path.
 *
 * Scoped to the screen (the default `hiltViewModel()` lifecycle) rather than per note card, so
 * every unresolved reference on the same screen shares one instance.
 *
 * The citing note's own `PagingSource` deliberately does not observe the table this fetch writes
 * to — see `FeedPagingSourceDaoReturnTypeConverter`'s doc for why — so a successful fetch alone
 * never makes the card re-render. [resolvedUris] is this ViewModel's own in-memory state, the
 * same idea as the note editor's local retry, so the card can update itself directly instead of
 * waiting for a live query that will never fire.
 */
@HiltViewModel
class NoteUnknownEventRetryViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    private val _resolvedUris = MutableStateFlow<Map<String, NoteNostrUriUi>>(emptyMap())
    val resolvedUris = _resolvedUris.asStateFlow()

    fun retry(eventId: String, uri: String) {
        val noteId = uri.extractNoteId() ?: return
        viewModelScope.launch {
            try {
                feedRepository.fetchConversation(
                    userId = activeAccountStore.activeUserId(),
                    noteId = noteId,
                    limit = 1,
                )
                feedRepository.findResolvedNostrUri(eventId = eventId, uri = uri)?.let { reference ->
                    _resolvedUris.update { it + (uri to reference.asNoteNostrUriUi()) }
                }
            } catch (error: NetworkException) {
                Napier.w(error) { "Failed to retry unknown event uri=$uri" }
            }
        }
    }
}
