package net.primal.android.notes.translate

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess

internal class TranslateApiImpl(private val httpClient: HttpClient) : TranslateApi {

    override suspend fun translate(serverUrl: String, text: String, targetLanguage: String): String {
        require(serverUrl.startsWith("https://")) {
            "Refusing to send note text to a non-HTTPS translation server: $serverUrl"
        }
        val response = httpClient.post("${serverUrl.trimEnd('/')}/translate") {
            setBody(TranslateRequest(q = text, target = targetLanguage))
        }
        if (!response.status.isSuccess()) {
            error("Translation request failed with status ${response.status}")
        }
        return response.body<TranslateResponse>().translatedText
    }
}
