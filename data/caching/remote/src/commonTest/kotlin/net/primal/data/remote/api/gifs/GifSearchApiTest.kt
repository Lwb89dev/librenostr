package net.primal.data.remote.api.gifs

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest
import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifSource

class GifSearchApiTest {

    // Trimmed from the shape documented in gifs.nostr.build/api/v1/openapi.json.
    private val nostrBuildPage = """
        {"build":"b1","gen":1,"q":"gm","semantic":false,"count":3,"offset":0,"limit":2,
         "items":[
           {"id":"aa.gif","url":"https://image.nostr.build/aa.gif","width":480,"height":270,
            "frames":10,"duration":1.2,"bytes":123456,"format":"gif","title":"Good morning",
            "tags":[],"lqip":null,"mp4":null,
            "previews":{"small":{"width":1,"height":1,"animated":null,"still":"s"},
                        "medium":{"width":1,"height":1,"animated":null,"still":"m"},
                        "w240":{"width":240,"height":135,"animated":"https://p/aa-240.webp","still":"https://p/aa-240.png"},
                        "w480":{"width":480,"height":270,"animated":null,"still":"w"}}},
           {"id":"bb.gif","url":"https://image.nostr.build/bb.gif","width":100,"height":100,
            "frames":1,"duration":null,"bytes":null,"format":"gif","title":"",
            "tags":[],"lqip":null,"mp4":null,
            "previews":{"small":{"width":1,"height":1,"animated":null,"still":"s"},
                        "medium":{"width":1,"height":1,"animated":null,"still":"m"},
                        "w240":{"width":100,"height":100,"animated":null,"still":"https://p/bb-still.png"},
                        "w480":{"width":100,"height":100,"animated":null,"still":"w"}}}
         ]}
    """.trimIndent()

    // Trimmed from a real gifverse.net/api/v1/search answer.
    private val gifversePage = """
        {"results":[
           {"i":"37CEHFnK","ti":"GM pepe","de":"a frog","w":356,"h":498,"s":255135,"f":["mp4"],"nsfw":false},
           {"i":"nsfwOne","ti":"nope","w":10,"h":10,"nsfw":true}
         ],
         "pagination":{"total":70,"limit":2,"offset":0,"has_more":true},"query":"gm","took_ms":22}
    """.trimIndent()

    // The API's real answer to an unregistered client, verbatim.
    private val notRegistered = """
        {"error":{"code":"client_not_registered",
         "message":"This client is not registered for the nostr.build GIF API"}}
    """.trimIndent()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    @Test
    fun nostrBuild_mapsItemsAndPagesWithinTheListCount() =
        runTest {
            val api = NostrBuildGifApi(client { json(nostrBuildPage) })

            val page = api.search(query = "gm", offset = 0)

            page.source shouldBe GifSource.NostrBuild
            page.results.map { it.id } shouldBe listOf("aa.gif", "bb.gif")
            page.results[0].previewUrl shouldBe "https://p/aa-240.webp"
            page.results[0].sizeBytes shouldBe 123456L
            page.results[0].mimeType shouldBe "image/gif"
            // No animated rendition: the grid falls back to the first frame.
            page.results[1].previewUrl shouldBe "https://p/bb-still.png"
            page.nextCursor shouldBe GifCursor(source = GifSource.NostrBuild, offset = 2)
        }

    @Test
    fun gifverse_dropsNsfwAndPostsTheOriginal() =
        runTest {
            val api = GifverseGifApi(client { json(gifversePage) })

            val page = api.search(query = "gm", offset = 0)

            page.results.map { it.id } shouldBe listOf("37CEHFnK")
            page.results.single().url shouldBe "https://gifverse.net/media/37CEHFnK/original.gif"
            page.nextCursor shouldBe GifCursor(source = GifSource.Gifverse, offset = 2)
        }

    @Test
    fun fallback_whenNostrBuildRefusesUs_answersFromGifverseAndStopsAskingForAWhile() =
        runTest {
            val requestedHosts = mutableListOf<String>()
            val http = client { request ->
                requestedHosts += request.url.host
                when (request.url.host) {
                    "gifs.nostr.build" -> json(notRegistered, status = HttpStatusCode.Forbidden)
                    else -> json(gifversePage)
                }
            }
            val timeSource = TestTimeSource()
            val api = FallbackGifSearchApi(
                nostrBuild = NostrBuildGifApi(http),
                gifverse = GifverseGifApi(http),
                timeSource = timeSource,
            )

            api.search(query = "gm").source shouldBe GifSource.Gifverse
            api.search(query = "gn").source shouldBe GifSource.Gifverse
            api.suggest(query = "gm") shouldBe emptyList()
            // One refusal is enough: the second search and the suggestions skipped nostr.build.
            requestedHosts.count { it == "gifs.nostr.build" } shouldBe 1

            timeSource += FallbackGifSearchApi.NOT_REGISTERED_PAUSE
            api.search(query = "gm")
            requestedHosts.count { it == "gifs.nostr.build" } shouldBe 2
        }

    @Test
    fun fallback_laterPagesStayWithTheProviderOfTheFirst() =
        runTest {
            val requestedHosts = mutableListOf<String>()
            val http = client { request ->
                requestedHosts += request.url.host
                when (request.url.host) {
                    "gifs.nostr.build" -> json(nostrBuildPage)
                    else -> json(gifversePage)
                }
            }
            val api = FallbackGifSearchApi(nostrBuild = NostrBuildGifApi(http), gifverse = GifverseGifApi(http))

            api.search(query = "gm", cursor = GifCursor(source = GifSource.Gifverse, offset = 30))

            requestedHosts shouldBe listOf("gifverse.net")
        }
}
