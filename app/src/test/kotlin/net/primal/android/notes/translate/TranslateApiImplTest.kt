package net.primal.android.notes.translate

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TranslateApiImplTest {

    private fun buildMockHttpClient(responseStatus: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) { json() }
            defaultRequest { contentType(ContentType.Application.Json) }
            engine {
                addHandler {
                    if (responseStatus.isSuccess()) {
                        respond(
                            content = """{"translatedText":"Ciao mondo"}""",
                            status = responseStatus,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respondError(responseStatus)
                    }
                }
            }
        }
    }

    @Test
    fun `translate posts to server-slash-translate and returns translatedText`() = runTest {
        val httpClient = buildMockHttpClient()
        val api: TranslateApi = TranslateApiImpl(httpClient = httpClient)

        val result = api.translate(
            serverUrl = "https://translate.example.com",
            text = "Hello world",
            targetLanguage = "it",
        )

        result shouldBe "Ciao mondo"
    }

    @Test
    fun `translate trims a trailing slash from the server url`() = runTest {
        var requestedUrl = ""
        val httpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) { json() }
            defaultRequest { contentType(ContentType.Application.Json) }
            engine {
                addHandler { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        content = """{"translatedText":"Ciao mondo"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val api: TranslateApi = TranslateApiImpl(httpClient = httpClient)

        api.translate(serverUrl = "https://translate.example.com/", text = "Hello", targetLanguage = "it")

        requestedUrl shouldBe "https://translate.example.com/translate"
    }

    @Test(expected = IllegalStateException::class)
    fun `translate throws when the server responds with an error status`() = runTest {
        val httpClient = buildMockHttpClient(responseStatus = HttpStatusCode.InternalServerError)
        val api: TranslateApi = TranslateApiImpl(httpClient = httpClient)

        api.translate(serverUrl = "https://translate.example.com", text = "Hello", targetLanguage = "it")
    }
}
