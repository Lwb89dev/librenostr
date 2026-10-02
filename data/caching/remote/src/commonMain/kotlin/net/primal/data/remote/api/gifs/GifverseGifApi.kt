package net.primal.data.remote.api.gifs

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ParametersBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifResult
import net.primal.data.remote.api.gifs.model.GifSearchPage
import net.primal.data.remote.api.gifs.model.GifSource

/**
 * GIFverse (`gifverse.net/api/v1`), the open GIF index Ditto's composer uses: no key, no
 * registration, a real trending list. Here it is only the fallback for [NostrBuildGifApi], which
 * is what the picker is meant to show but which refuses clients it has not registered.
 *
 * Mirrors Ditto's `useGifSearch` hook: the posted file is `/media/<id>/original.gif`, and results
 * the index flags as NSFW are dropped client side. GIFverse publishes no smaller still or animated
 * preview of a GIF (only the original and a video rendition), so the grid shows the original too —
 * heavier than nostr.build's previews, which is one more reason it is only the fallback.
 */
internal class GifverseGifApi(
    private val httpClient: HttpClient,
) {

    suspend fun search(query: String, offset: Int): GifSearchPage =
        fetch(path = "search", offset = offset) {
            append("q", query.take(MAX_QUERY_LENGTH))
            append("sort", "relevant")
        }

    suspend fun trending(offset: Int): GifSearchPage =
        fetch(path = "trending", offset = offset) {
            append("sort", "popular")
        }

    private suspend fun fetch(
        path: String,
        offset: Int,
        parameters: ParametersBuilder.() -> Unit,
    ): GifSearchPage {
        val response = httpClient.get("$BASE_URL/$path") {
            url.parameters.parameters()
            url.parameters.append("limit", PAGE_SIZE.toString())
            url.parameters.append("offset", offset.toString())
        }
        val page = json.decodeFromString<Response>(response.bodyOrThrow())
        val next = page.pagination.offset + page.results.size
        val hasMore = page.pagination.hasMore && page.results.isNotEmpty()
        return GifSearchPage(
            results = page.results.filterNot { it.nsfw }.mapNotNull { it.toGifResult() },
            source = GifSource.Gifverse,
            nextCursor = if (hasMore) GifCursor(source = GifSource.Gifverse, offset = next) else null,
        )
    }

    private suspend fun HttpResponse.bodyOrThrow(): String {
        val body = bodyAsText()
        if (status.isSuccess()) return body
        throw when (status) {
            HttpStatusCode.TooManyRequests -> GifProviderException.RateLimited(provider = GifSource.Gifverse)
            else -> GifProviderException.Unavailable(provider = GifSource.Gifverse, status = status.value)
        }
    }

    private fun Gif.toGifResult(): GifResult? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        val url = "$MEDIA_URL/$id/original.gif"
        return GifResult(
            id = id,
            url = url,
            previewUrl = url,
            mimeType = "image/gif",
            width = width.takeIf { it > 0 } ?: DEFAULT_WIDTH,
            height = height.takeIf { it > 0 } ?: DEFAULT_HEIGHT,
            sizeBytes = size?.takeIf { it > 0 },
            title = title?.takeIf { it.isNotBlank() } ?: description.orEmpty(),
        )
    }

    @Serializable
    private data class Response(
        val results: List<Gif> = emptyList(),
        val pagination: Pagination = Pagination(),
    )

    // GIFverse abbreviates its field names to keep its payloads small.
    @Serializable
    private data class Gif(
        @SerialName("i") val id: String? = null,
        @SerialName("ti") val title: String? = null,
        @SerialName("de") val description: String? = null,
        @SerialName("w") val width: Int = 0,
        @SerialName("h") val height: Int = 0,
        @SerialName("s") val size: Long? = null,
        val nsfw: Boolean = false,
    )

    @Serializable
    private data class Pagination(
        val offset: Int = 0,
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    private companion object {
        const val BASE_URL = "https://gifverse.net/api/v1"
        const val MEDIA_URL = "https://gifverse.net/media"
        const val PAGE_SIZE = 30
        const val MAX_QUERY_LENGTH = 500

        // Ditto's fallback size for a result that reports no dimensions.
        const val DEFAULT_WIDTH = 220
        const val DEFAULT_HEIGHT = 160

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
