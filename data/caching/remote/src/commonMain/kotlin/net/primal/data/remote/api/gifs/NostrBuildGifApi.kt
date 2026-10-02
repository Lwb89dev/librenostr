package net.primal.data.remote.api.gifs

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifResult
import net.primal.data.remote.api.gifs.model.GifSearchPage
import net.primal.data.remote.api.gifs.model.GifSource

/**
 * nostr.build's GIF search (`gifs.nostr.build/api/v1`, announced by nostr.build's founder on
 * 2026-09-26): over 110k GIFs taken from nostr.build's free public upload pool, so every result is
 * media that already lives on a Nostr media host and can be posted by URL as is.
 *
 * The API only answers registered clients — web clients by `Origin`, native apps by API key or
 * `User-Agent` (from its OpenAPI description at `/api/v1/openapi.json`). Every request here goes
 * through the app's shared Ktor client, which already identifies as `LibreNostr/<version>`, so
 * registering that User-Agent with nostr.build is all it takes to switch this on; until then the
 * API answers 403 `client_not_registered`, which surfaces as [GifProviderException.NotRegistered]
 * and makes [FallbackGifSearchApi] use GIFverse instead.
 *
 * There is no trending endpoint. What it does have, and what zap.observer (the first client to use
 * it) builds its picker from, is plain search plus autocomplete; [trending] is a search for a
 * Nostr staple, and the picker offers more such topics as chips.
 */
internal class NostrBuildGifApi(
    private val httpClient: HttpClient,
) {

    suspend fun search(query: String, offset: Int): GifSearchPage {
        val response = httpClient.get("$BASE_URL/search") {
            url.parameters.append("q", query.take(MAX_QUERY_LENGTH))
            url.parameters.append("limit", PAGE_SIZE.toString())
            url.parameters.append("offset", offset.toString())
            // The default, but spelled out: adult GIFs stay out of a picker anyone can open.
            url.parameters.append("safe", "1")
        }
        val body = response.bodyOrThrow()
        val page = json.decodeFromString<SearchResponse>(body)
        val results = page.items.mapNotNull { it.toGifResult() }
        // `count` is the length of this query's whole list (capped at 200 by the API), and an
        // offset past 199 is rejected outright, so paging stops at whichever comes first.
        val next = page.offset + page.items.size
        val hasMore = page.items.isNotEmpty() && next < page.count && next <= MAX_OFFSET
        return GifSearchPage(
            results = results,
            source = GifSource.NostrBuild,
            nextCursor = if (hasMore) GifCursor(source = GifSource.NostrBuild, offset = next) else null,
        )
    }

    suspend fun trending(offset: Int): GifSearchPage = search(query = TRENDING_QUERY, offset = offset)

    suspend fun suggest(query: String): List<String> {
        val response = httpClient.get("$BASE_URL/suggest") {
            url.parameters.append("q", query.take(MAX_QUERY_LENGTH))
            url.parameters.append("limit", SUGGESTION_LIMIT.toString())
            url.parameters.append("safe", "1")
        }
        val body = response.bodyOrThrow()
        return json.decodeFromString<SuggestResponse>(body).terms
            .mapNotNull { it.term?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
    }

    private suspend fun HttpResponse.bodyOrThrow(): String {
        val body = bodyAsText()
        if (status.isSuccess()) return body
        throw when {
            status == HttpStatusCode.Forbidden && NOT_REGISTERED_CODE in body ->
                GifProviderException.NotRegistered(provider = GifSource.NostrBuild)
            status == HttpStatusCode.TooManyRequests ->
                GifProviderException.RateLimited(provider = GifSource.NostrBuild)
            else -> GifProviderException.Unavailable(provider = GifSource.NostrBuild, status = status.value)
        }
    }

    /**
     * The smallest animated preview that fills a picker cell: `w240` (at most 240 px wide) is what
     * the API documents for column grids on phone-sized screens. Its `animated` member is null when
     * the GIF is too large to animate, and then only its first frame (`still`) exists.
     */
    private fun Gif.toGifResult(): GifResult? {
        val mimeType = MIME_TYPES[format]
        val preview = previews?.w240 ?: previews?.medium
        val previewUrl = preview?.animated?.takeIf { it.isNotBlank() } ?: preview?.still?.takeIf { it.isNotBlank() }
        val incomplete = id.isNullOrBlank() || url.isNullOrBlank() || mimeType == null || previewUrl == null
        if (incomplete || width <= 0 || height <= 0) return null
        return GifResult(
            id = id,
            url = url,
            previewUrl = previewUrl,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = bytes?.takeIf { it > 0 },
            title = title.orEmpty(),
        )
    }

    @Serializable
    private data class SearchResponse(
        val count: Int = 0,
        val offset: Int = 0,
        val items: List<Gif> = emptyList(),
    )

    @Serializable
    private data class Gif(
        val id: String? = null,
        val url: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val bytes: Long? = null,
        val format: String? = null,
        val title: String? = null,
        val previews: Previews? = null,
    )

    @Serializable
    private data class Previews(
        val medium: Preview? = null,
        val w240: Preview? = null,
    )

    @Serializable
    private data class Preview(
        val animated: String? = null,
        val still: String? = null,
    )

    @Serializable
    private data class SuggestResponse(
        val terms: List<Term> = emptyList(),
    )

    @Serializable
    private data class Term(
        val term: String? = null,
    )

    private companion object {
        const val BASE_URL = "https://gifs.nostr.build/api/v1"
        const val NOT_REGISTERED_CODE = "client_not_registered"
        const val TRENDING_QUERY = "gm"
        const val PAGE_SIZE = 36
        const val MAX_OFFSET = 199
        const val MAX_QUERY_LENGTH = 500
        const val SUGGESTION_LIMIT = 8

        /** The two formats the index serves; anything else is skipped rather than guessed at. */
        val MIME_TYPES = mapOf("gif" to "image/gif", "webp" to "image/webp")

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
