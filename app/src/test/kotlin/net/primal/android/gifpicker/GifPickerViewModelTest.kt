package net.primal.android.gifpicker

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.android.gifpicker.domain.GifItem
import net.primal.core.testing.CoroutinesTestRule
import net.primal.data.remote.api.gifs.GifSearchApi
import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifResult
import net.primal.data.remote.api.gifs.model.GifSearchPage
import net.primal.data.remote.api.gifs.model.GifSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class GifPickerViewModelTest {

    @get:Rule
    val coroutineTestRule = CoroutinesTestRule()

    private fun gif(id: String) =
        GifResult(
            id = id,
            url = "https://example.com/$id.gif",
            previewUrl = "https://example.com/$id-w240.webp",
            mimeType = "image/gif",
            width = 320,
            height = 240,
        )

    private fun page(
        vararg ids: String,
        source: GifSource = GifSource.NostrBuild,
        next: GifCursor? = null,
    ) = GifSearchPage(results = ids.map { gif(it) }, source = source, nextCursor = next)

    @Test
    fun creatingTheViewModel_doesNotFetchAnything() =
        runTest {
            // The composer builds its picker's view model as soon as it opens; a page of GIFs
            // must not be downloaded for every note composed without ever opening the picker.
            val api = mockk<GifSearchApi>(relaxed = true)
            GifPickerViewModel(gifSearchApi = api)
            advanceUntilIdle()

            coVerify(exactly = 0) { api.trending(cursor = any()) }
        }

    @Test
    fun pickerShown_loadsTrendingAndRecordsTheSource() =
        runTest {
            val api = mockk<GifSearchApi> {
                coEvery { trending(cursor = null) } returns page("t1", "t2", source = GifSource.Gifverse)
            }
            val viewModel = GifPickerViewModel(gifSearchApi = api)

            viewModel.setEvent(GifPickerContract.UiEvent.PickerShown)
            advanceUntilIdle()

            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("t1", "t2")
            viewModel.state.value.source shouldBe GifSource.Gifverse
        }

    @Test
    fun pickerShownAgain_keepsTheLoadedResults() =
        runTest {
            val api = mockk<GifSearchApi> {
                coEvery { trending(cursor = null) } returns page("t1")
            }
            val viewModel = GifPickerViewModel(gifSearchApi = api)

            viewModel.setEvent(GifPickerContract.UiEvent.PickerShown)
            advanceUntilIdle()
            viewModel.setEvent(GifPickerContract.UiEvent.PickerShown)
            advanceUntilIdle()

            coVerify(exactly = 1) { api.trending(cursor = null) }
        }

    @Test
    fun loadMoreGifs_continuesFromTheCursorOfTheVisibleListing() =
        runTest {
            val cursor = GifCursor(source = GifSource.NostrBuild, offset = 36)
            val api = mockk<GifSearchApi> {
                coEvery { trending(cursor = null) } returns page("t1", next = cursor)
                coEvery { trending(cursor = cursor) } returns page("t2")
            }
            val viewModel = GifPickerViewModel(gifSearchApi = api)
            viewModel.setEvent(GifPickerContract.UiEvent.PickerShown)
            advanceUntilIdle()

            viewModel.setEvent(GifPickerContract.UiEvent.LoadMoreGifs)
            advanceUntilIdle()

            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("t1", "t2")
        }

    @Test
    fun clearingTheSearchQuery_revertsToTrending() =
        runTest {
            val api = mockk<GifSearchApi> {
                coEvery { trending(cursor = null) } returns page("trending")
                coEvery { search(query = "cats", cursor = null) } returns page("cat1")
                coEvery { suggest(query = any()) } returns emptyList()
            }
            val viewModel = GifPickerViewModel(gifSearchApi = api)
            viewModel.setEvent(GifPickerContract.UiEvent.PickerShown)
            advanceUntilIdle()

            viewModel.setEvent(GifPickerContract.UiEvent.UpdateSearchQuery(query = "cats"))
            advanceUntilIdle()
            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("cat1")

            viewModel.setEvent(GifPickerContract.UiEvent.UpdateSearchQuery(query = ""))
            advanceUntilIdle()

            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("trending")
            viewModel.state.value.topics shouldBe GifPickerContract.DEFAULT_TOPICS
        }

    @Test
    fun typing_replacesTheTopicChipsWithSuggestions() =
        runTest {
            val api = mockk<GifSearchApi> {
                coEvery { search(query = "bitc", cursor = null) } returns page("b1")
                coEvery { suggest(query = "bitc") } returns listOf("bitcoin", "bitc", "bitcoin pizza")
            }
            val viewModel = GifPickerViewModel(gifSearchApi = api)
            // Lets the debounced collector subscribe first: under the test dispatcher it starts
            // one dispatch later than the event would be emitted (in the app, viewModelScope's
            // immediate dispatcher subscribes it during construction).
            advanceUntilIdle()

            viewModel.setEvent(GifPickerContract.UiEvent.UpdateSearchQuery(query = "bitc"))
            advanceUntilIdle()

            // The exact text already typed is not offered back as a chip.
            viewModel.state.value.topics shouldBe listOf("bitcoin", "bitcoin pizza")
        }

    @Test
    fun selectingAGif_emitsTheWholeItem() =
        runTest {
            val api = mockk<GifSearchApi>(relaxed = true)
            val viewModel = GifPickerViewModel(gifSearchApi = api)
            val item = GifItem(
                id = "g",
                url = "https://example.com/g.gif",
                previewUrl = "https://example.com/g.webp",
                width = 10,
                height = 20,
            )

            viewModel.setEvent(GifPickerContract.UiEvent.SelectGif(item))
            advanceUntilIdle()

            viewModel.effect.first() shouldBe GifPickerContract.SideEffect.GifSelected(item)
        }
}
