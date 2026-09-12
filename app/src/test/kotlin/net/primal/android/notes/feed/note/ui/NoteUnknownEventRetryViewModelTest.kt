package net.primal.android.notes.feed.note.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.testing.CoroutinesTestRule
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.links.EventUriNostrReference
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.posts.FeedRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the fix for a retry that fetched successfully but never visibly did anything: the citing
 * note's own PagingSource deliberately does not observe the table the fetch writes to (see
 * FeedPagingSourceDaoReturnTypeConverter's doc), so the card only updates if this ViewModel keeps
 * its own local record of what just got resolved — the same idea as the note editor's in-memory
 * retry — instead of waiting for a live query that will never fire.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class NoteUnknownEventRetryViewModelTest {

    @get:Rule
    val coroutineTestRule = CoroutinesTestRule()

    private val citingEventId = "c".repeat(64)
    private val quotedNoteId = "b".repeat(64)
    private val uri = "nostr:${quotedNoteId.hexToNoteHrp()}"

    private fun activeAccountStore(): ActiveAccountStore =
        mockk {
            every { activeUserId() } returns "alice"
        }

    @Test
    fun retry_whenCitationResolves_exposesItInResolvedUris() =
        runTest {
            val reference = EventUriNostrReference(
                eventId = citingEventId,
                uri = uri,
                type = EventUriNostrType.Note,
            )
            val feedRepository = mockk<FeedRepository> {
                coEvery { fetchConversation(userId = any(), noteId = quotedNoteId, limit = 1) } returns Unit
                coEvery { findResolvedNostrUri(eventId = citingEventId, uri = uri) } returns reference
            }
            val viewModel = NoteUnknownEventRetryViewModel(
                feedRepository = feedRepository,
                activeAccountStore = activeAccountStore(),
            )

            viewModel.resolvedUris.value shouldBe emptyMap()

            viewModel.retry(eventId = citingEventId, uri = uri)
            runCurrent()

            viewModel.resolvedUris.value[uri]?.type shouldBe EventUriNostrType.Note
        }

    @Test
    fun retry_whenStillUnresolved_leavesResolvedUrisEmpty() =
        runTest {
            val feedRepository = mockk<FeedRepository> {
                coEvery { fetchConversation(userId = any(), noteId = quotedNoteId, limit = 1) } returns Unit
                coEvery { findResolvedNostrUri(eventId = citingEventId, uri = uri) } returns null
            }
            val viewModel = NoteUnknownEventRetryViewModel(
                feedRepository = feedRepository,
                activeAccountStore = activeAccountStore(),
            )

            viewModel.retry(eventId = citingEventId, uri = uri)
            runCurrent()

            viewModel.resolvedUris.value[uri].shouldBeNull()
        }

    @Test
    fun retry_whenFetchFails_doesNotCrashAndLeavesResolvedUrisEmpty() =
        runTest {
            val feedRepository = mockk<FeedRepository> {
                coEvery { fetchConversation(userId = any(), noteId = quotedNoteId, limit = 1) } throws NetworkException()
            }
            val viewModel = NoteUnknownEventRetryViewModel(
                feedRepository = feedRepository,
                activeAccountStore = activeAccountStore(),
            )

            viewModel.retry(eventId = citingEventId, uri = uri)
            runCurrent()

            viewModel.resolvedUris.value shouldBe emptyMap()
        }
}
