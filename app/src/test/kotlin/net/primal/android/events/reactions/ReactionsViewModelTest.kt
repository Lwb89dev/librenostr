package net.primal.android.events.reactions

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.primal.android.navigation.EVENT_ID
import net.primal.android.navigation.INITIAL_REACTION_TYPE
import net.primal.core.testing.CoroutinesTestRule
import net.primal.domain.events.EventRepository
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.ReactionType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins that likes and reposts each carry their own loading flag.
 *
 * Before this, both fetches shared one `loading` boolean and ran concurrently: whichever finished
 * first — reposts, almost always, being the shorter list — flipped it to false for both tabs. The
 * likes tab then rendered its still-empty list as "no likes yet" for however long the slower
 * likes fetch was still in flight, before flipping back to the real result. Tapping "who liked
 * this" is exactly the case that surfaced it.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ReactionsViewModelTest {

    @get:Rule
    val coroutineTestRule = CoroutinesTestRule()

    private fun savedStateHandle() = SavedStateHandle(
        mapOf(
            EVENT_ID to "a".repeat(64),
            INITIAL_REACTION_TYPE to ReactionType.LIKES.name,
        ),
    )

    @Test
    fun fetch_fastRepostsDoesNotClearLoadingForSlowerLikes() =
        runTest {
            val likesGate = CompletableDeferred<Unit>()
            val eventRepository = mockk<EventRepository> {
                every { pagedEventZaps(userId = any(), eventId = any(), articleATag = any(), zapKind = any()) } returns
                    emptyFlow()
                coEvery { fetchEventActions(eventId = any(), kind = NostrEventKind.ShortTextNoteRepost.value) } returns
                    emptyList()
                coEvery { fetchEventActions(eventId = any(), kind = NostrEventKind.Reaction.value) } coAnswers {
                    likesGate.await()
                    emptyList()
                }
            }

            val viewModel = ReactionsViewModel(
                savedStateHandle = savedStateHandle(),
                activeAccountStore = mockk(relaxed = true),
                dispatcherProvider = coroutineTestRule.dispatcherProvider,
                eventRepository = eventRepository,
            )
            runCurrent()

            // Reposts already resolved; likes is still stuck behind the gate.
            viewModel.state.value.repostsLoading shouldBe false
            viewModel.state.value.likesLoading shouldBe true

            likesGate.complete(Unit)
            runCurrent()

            viewModel.state.value.likesLoading shouldBe false
        }
}
