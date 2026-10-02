package net.primal.data.remote.api.gifs

import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifSearchPage

/**
 * GIF search for the composer's picker.
 *
 * Paging is cursor based on purpose: the implementation may answer the first page from one
 * provider and would happily answer the second from another (see [FallbackGifSearchApi]), and an
 * offset into one provider's list means nothing in the other's. A [GifCursor] carries the provider
 * that produced the page it continues, so every later page comes from the same list.
 */
interface GifSearchApi {

    /** GIFs matching [query]; pass the previous page's [GifSearchPage.nextCursor] to continue. */
    suspend fun search(query: String, cursor: GifCursor? = null): GifSearchPage

    /** What to show before anything is typed; paged like [search]. */
    suspend fun trending(cursor: GifCursor? = null): GifSearchPage

    /**
     * Search terms completing [query], best first; empty when the active provider has no
     * autocomplete. Never throws for a provider failure: suggestions are a nicety, not a result.
     */
    suspend fun suggest(query: String): List<String>
}
