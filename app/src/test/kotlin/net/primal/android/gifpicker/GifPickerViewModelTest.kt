package net.primal.android.gifpicker

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.core.testing.CoroutinesTestRule
import net.primal.data.remote.api.klipy.KlipyApi
import net.primal.data.remote.api.klipy.model.KlipyGif
import net.primal.data.remote.api.klipy.model.KlipyMediaFormat
import net.primal.data.remote.api.klipy.model.KlipySearchResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the fix for a GIF picker that opened to a blank screen: [GifPickerViewModel.fetchTrending]
 * and its suspend counterpart existed, fully working, but nothing in `init` or the empty-query
 * branch of the search debounce ever called them, and "load more" with no active search was a
 * silent no-op — so trending GIFs never showed until the user typed something.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class GifPickerViewModelTest {

    @get:Rule
    val coroutineTestRule = CoroutinesTestRule()

    private fun gif(id: String) = KlipyGif(
        id = id,
        mediaFormats = mapOf(
            "tinygif" to KlipyMediaFormat(url = "https://example.com/$id-tiny.gif"),
            "gif" to KlipyMediaFormat(url = "https://example.com/$id.gif"),
        ),
    )

    @Test
    fun init_fetchesTrendingImmediately() =
        runTest {
            val klipyApi = mockk<KlipyApi> {
                coEvery { fetchTrendingGifs(limit = any(), cursor = null) } returns
                    KlipySearchResponse(results = listOf(gif("t1"), gif("t2")), next = "cursor-2")
            }
            val viewModel = GifPickerViewModel(klipyApi = klipyApi)
            advanceUntilIdle()

            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("t1", "t2")
        }

    @Test
    fun loadMoreGifs_withNoActiveSearch_paginatesTrending() =
        runTest {
            val klipyApi = mockk<KlipyApi> {
                coEvery { fetchTrendingGifs(limit = any(), cursor = null) } returns
                    KlipySearchResponse(results = listOf(gif("t1")), next = "cursor-2")
                coEvery { fetchTrendingGifs(limit = any(), cursor = "cursor-2") } returns
                    KlipySearchResponse(results = listOf(gif("t2")), next = null)
            }
            val viewModel = GifPickerViewModel(klipyApi = klipyApi)
            advanceUntilIdle()

            viewModel.setEvent(GifPickerContract.UiEvent.LoadMoreGifs)
            advanceUntilIdle()

            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("t1", "t2")
        }

    @Test
    fun clearingTheSearchQuery_revertsToTrending() =
        runTest {
            val klipyApi = mockk<KlipyApi> {
                coEvery { fetchTrendingGifs(limit = any(), cursor = null) } returns
                    KlipySearchResponse(results = listOf(gif("trending")))
                coEvery { searchGifs(query = "cats", limit = any(), cursor = null) } returns
                    KlipySearchResponse(results = listOf(gif("cat1")))
            }
            val viewModel = GifPickerViewModel(klipyApi = klipyApi)
            advanceUntilIdle()

            viewModel.setEvent(GifPickerContract.UiEvent.UpdateSearchQuery(query = "cats"))
            advanceUntilIdle()
            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("cat1")

            viewModel.setEvent(GifPickerContract.UiEvent.UpdateSearchQuery(query = ""))
            advanceUntilIdle()

            viewModel.state.value.gifItems.map { it.id } shouldBe listOf("trending")
        }
}
