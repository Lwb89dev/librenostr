package net.primal.data.remote.api.klipy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.readRawBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.data.remote.api.klipy.model.KlipySearchResponse
import net.primal.data.remote.api.klipy.model.KlipyGif
import net.primal.data.remote.api.klipy.model.KlipyMediaFormat

internal class KlipyApiImpl(
    private val httpClient: HttpClient,
) : KlipyApi {

    override suspend fun fetchTrendingGifs(limit: Int, cursor: String?): KlipySearchResponse =
        fetchCommonsGifs(query = "animated gif", limit = limit, offset = cursor?.toIntOrNull() ?: 0)

    override suspend fun searchGifs(
        query: String,
        limit: Int,
        cursor: String?,
    ): KlipySearchResponse = fetchCommonsGifs(
        query = query,
        limit = limit,
        offset = cursor?.toIntOrNull() ?: 0,
    )

    override suspend fun registerShare(gifId: String, query: String) = Unit

    override suspend fun downloadGifBytes(url: String): ByteArray =
        httpClient.get(url) {
            headers {
                append(HttpHeaders.UserAgent, LIBRENOSTR_USER_AGENT)
                append(HttpHeaders.Referrer, COMMONS_REFERRER)
            }
        }.readRawBytes()

    private suspend fun fetchCommonsGifs(query: String, limit: Int, offset: Int): KlipySearchResponse {
        val response = httpClient.get("$COMMONS_API/w/api.php") {
            headers { append(HttpHeaders.UserAgent, LIBRENOSTR_USER_AGENT) }
            url.parameters.append("action", "query")
            url.parameters.append("generator", "search")
            url.parameters.append("gsrsearch", "$query filemime:image/gif")
            url.parameters.append("gsrnamespace", "6")
            url.parameters.append("gsrlimit", limit.coerceIn(1, 50).toString())
            url.parameters.append("gsroffset", offset.toString())
            url.parameters.append("prop", "imageinfo")
            url.parameters.append("iiprop", "url|mime")
            url.parameters.append("iiurlwidth", "320")
            // Ask Commons for a small thumbnail. Coil can render it reliably and we never
            // download the original (often multi-megabyte) animated GIF for the grid.
            url.parameters.append("format", "json")
            url.parameters.append("origin", "*")
        }
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject.orEmpty()
        val results = pages.values.mapNotNull { page ->
            val pageObject = page.jsonObject
            val imageInfo = pageObject["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@mapNotNull null
            val url = imageInfo["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            // Never fall back to the original GIF: Commons originals can be many
            // megabytes and defeat the picker's low-data preview behavior.
            val preview = imageInfo["thumburl"]?.jsonPrimitive?.content ?: return@mapNotNull null
            KlipyGif(
                id = pageObject["pageid"]?.jsonPrimitive?.content ?: url,
                contentDescription = pageObject["title"]?.jsonPrimitive?.content.orEmpty(),
                mediaFormats = mapOf(
                    "gif" to KlipyMediaFormat(url = url),
                    "tinygif" to KlipyMediaFormat(url = preview),
                ),
            )
        }
        return KlipySearchResponse(
            results = results,
            next = if (results.size >= limit) (offset + limit).toString() else null,
        )
    }

    companion object {
        private const val COMMONS_API = "https://commons.wikimedia.org"
        private const val COMMONS_REFERRER = "$COMMONS_API/"
        private const val LIBRENOSTR_USER_AGENT = "LibreNostr/1.0"
    }
}
