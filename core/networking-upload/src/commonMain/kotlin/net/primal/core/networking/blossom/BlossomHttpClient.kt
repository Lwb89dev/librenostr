package net.primal.core.networking.blossom

import io.github.aakira.napier.Napier
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.primal.core.networking.UserAgentProvider
import net.primal.core.networking.factory.HttpClientFactory

private const val CONNECT_TIMEOUT_MILLIS = 15_000L

// Media uploads can legitimately take longer than a normal API request, especially on mobile.
private const val SOCKET_TIMEOUT_MILLIS = 120_000L

internal fun createBlossomHttpClient() =
    HttpClientFactory.createHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }

        install(ContentNegotiation) {
            json(
                json = Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                },
            )
        }

        defaultRequest {
            contentType(ContentType.Application.Json)
        }

        install(UserAgent) {
            agent = UserAgentProvider.resolveUserAgent()
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Napier.v(tag = "BlossomHttpClient") { message }
                }
            }
            // Requests carry a NIP-98-style Authorization header (a signed Nostr event with the
            // user's pubkey). HEADERS/BODY levels would put that in app_logs/*.jsonl, which the
            // in-app "Share Logs" feature can export to any app the user shares with.
            level = LogLevel.NONE
        }
    }.apply {
        // Required workaround to remove `ContentLength: 0`
        requestPipeline.intercept(HttpRequestPipeline.Render) {
            if (context.method == HttpMethod.Head) {
                context.headers.remove(HttpHeaders.ContentLength)
            }
            proceed()
        }
    }
