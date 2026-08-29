package net.primal.core.networking.blossom

import io.github.aakira.napier.Napier
import io.github.anvell.filetype.FileType
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asExpirationTag
import net.primal.domain.nostr.asHashtagTag
import net.primal.domain.nostr.asSha256Tag
import net.primal.domain.nostr.cryptography.NostrEventSignatureHandler
import net.primal.domain.nostr.cryptography.utils.unwrapOrThrow
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.use

internal class PrimalUploadService(
    private val dispatchers: DispatcherProvider,
    private val blossomResolver: BlossomServerListProvider,
    private val signatureHandler: NostrEventSignatureHandler? = null,
) {

    private val mirroringScope = CoroutineScope(SupervisorJob() + dispatchers.io())

    suspend fun upload(
        userId: String,
        openBufferedSource: () -> BufferedSource,
        onSignRequested: (suspend (NostrUnsignedEvent) -> NostrEvent)? = null,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)? = null,
    ): UploadResult {
        val result = withContext(dispatchers.io()) {
            runCatching {
                val blossomApis = resolveBlossomApisOrThrow(userId = userId)
                val mirrorApis = blossomApis.drop(1)

                val fileMetadata = openBufferedSource().use { it.getMetadata() }

                val uploadAuthorizationHeader = signAuthorizationOrThrow(
                    userId = userId,
                    fileHash = fileMetadata.sha256,
                    onSignRequested = onSignRequested,
                )

                var descriptor: BlobDescriptor? = null
                var lastUploadError: Throwable? = null
                blossomApis.forEach { blossomApi ->
                    if (descriptor != null) return@forEach
                    try {
                        // BUD-01 specifies /upload. A few older servers still
                        // expose /media, so keep it as a compatibility fallback.
                        // Try both for any transport/protocol failure: Ktor may
                        // throw a non-Blossom exception before we receive a HTTP
                        // response (for example on a reset connection).
                        descriptor = try {
                            blossomApi.putUpload(
                                authorization = uploadAuthorizationHeader,
                                fileMetadata = fileMetadata,
                                bufferedSource = openBufferedSource(),
                                onProgress = onProgress,
                            )
                        } catch (firstError: Throwable) {
                            if (firstError is CancellationException) throw firstError
                            Napier.w(firstError) { "Blossom /upload failed; trying legacy /media endpoint." }
                            blossomApi.putMedia(
                                authorization = uploadAuthorizationHeader,
                                fileMetadata = fileMetadata,
                                bufferedSource = openBufferedSource(),
                                onProgress = onProgress,
                            )
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        lastUploadError = error
                        Napier.w(error) { "Blossom upload failed; trying the next configured server." }
                    }
                }
                val uploadedDescriptor = descriptor
                    ?: throw BlossomUploadException(
                        message = "All configured Blossom servers rejected the upload.",
                        cause = lastUploadError,
                    )

                val mirrorAuthorizationHeader = signAuthorizationOrThrow(
                    userId = userId,
                    fileHash = uploadedDescriptor.sha256,
                    humanMessage = "Mirror File",
                    onSignRequested = onSignRequested,
                )

                mirrorApis.forEach { blossomApi ->
                    mirroringScope.launch {
                        runCatching {
                            blossomApi.putMirror(
                                authorization = mirrorAuthorizationHeader,
                                fileUrl = uploadedDescriptor.url,
                            )
                        }.onFailure { error ->
                            Napier.w(error) { "Blossom mirror failed for ${uploadedDescriptor.url}" }
                        }
                    }
                }

                return@runCatching uploadedDescriptor
            }
        }

        return try {
            val descriptor = result.getOrThrow()
            UploadResult.Success(
                remoteUrl = descriptor.url,
                originalFileSize = descriptor.sizeInBytes,
                originalHash = descriptor.sha256,
                nip94 = descriptor.nip94?.toNip94Metadata(),
            )
        } catch (error: Exception) {
            val uploadError = when {
                error is BlossomException -> error
                else -> BlossomException(cause = error)
            }
            UploadResult.Failed(
                error = uploadError,
                message = uploadError.message,
            )
        }
    }

    private suspend fun resolveBlossomApisOrThrow(userId: String): List<BlossomApi> {
        return blossomResolver.provideBlossomServerList(userId).mapNotNull {
            runCatching { BlossomApiFactory.create(baseBlossomUrl = it.trimTrailingSlashes()) }.getOrNull()
        }.ifEmpty {
            throw BlossomUploadException(cause = IllegalStateException("Invalid blossom server list."))
        }
    }

    private suspend fun signAuthorizationOrThrow(
        userId: String,
        fileHash: String,
        humanMessage: String? = null,
        onSignRequested: (suspend (NostrUnsignedEvent) -> NostrEvent)? = null,
    ): String {
        val unsignedAuthorizationEvent = NostrUnsignedEvent(
            kind = NostrEventKind.BlossomUploadBlob.value,
            pubKey = userId,
            content = humanMessage ?: "Upload File",
            tags = listOf(
                "upload".asHashtagTag(),
                fileHash.asSha256Tag(),
                expirationTimestamp().asExpirationTag(),
            ),
        )

        val signedAuthorizationEvent = onSignRequested?.invoke(unsignedAuthorizationEvent)
            ?: signatureHandler?.signNostrEvent(unsignedAuthorizationEvent)?.unwrapOrThrow()
            ?: error("Missing signature handler.")
        return signedAuthorizationEvent.buildAuthorizationHeader()
    }

    private fun NostrEvent.buildAuthorizationHeader(): String {
        val jsonPayload = this.encodeToJsonString()
        val base64Encoded = jsonPayload.encodeUtf8().base64()
        val authorizationHeader = "Nostr $base64Encoded"
        return authorizationHeader
    }

    // Nostr timestamps (including Blossom's expiration tag) are Unix seconds,
    // not milliseconds. Sending milliseconds makes every authorization appear
    // valid for tens of thousands of years and is rejected by compliant servers.
    private fun expirationTimestamp() = Clock.System.now().plus(1.hours).epochSeconds

    private fun BufferedSource.getMetadata(): FileMetadata {
        val hashingSource = HashingSource.sha256(this)
        val bufferedHashingSource = hashingSource.buffer()
        val blackhole = blackholeSink().buffer()
        val tempBuffer = Buffer()

        val mimeType = this.mimeType()
        var totalBytes = 0L
        bufferedHashingSource.use {
            while (!it.exhausted()) {
                val bytesRead = it.read(tempBuffer, DEFAULT_BUFFER_SIZE)
                if (bytesRead == -1L) break
                totalBytes += bytesRead
                blackhole.write(tempBuffer, bytesRead)
            }
        }

        val sha256Bytes = hashingSource.hash.toByteArray()
        val hex = sha256Bytes.joinToString("") {
            it.toInt().and(other = 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

        return FileMetadata(
            sizeInBytes = totalBytes,
            sha256 = hex,
            mimeType = mimeType,
        )
    }

    /**
     * Returns the RFC-6838 media-type (e.g. "image/png") or `null`
     * without consuming the source.
     */
    fun BufferedSource.mimeType(): String? {
        val head = peek().readByteArray(FILE_HEAD_PEEK_BYTES)
        return FileType.detect(head)?.toString()
    }

    private companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024L
        private const val FILE_HEAD_PEEK_BYTES = 512L
    }

    private fun String.trimTrailingSlashes(): String = this.trimEnd('/')
}
