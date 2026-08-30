package net.primal.core.networking.blossom

import io.ktor.http.Url
import net.primal.core.utils.coroutines.createDispatcherProvider

object BlossomApiFactory {

    fun create(baseBlossomUrl: String): BlossomApi {
        require(baseBlossomUrl.isSecureBlossomUrl()) { "Blossom server must use HTTPS." }
        return BlossomApiImpl(
            dispatcherProvider = createDispatcherProvider(),
            baseBlossomUrl = baseBlossomUrl,
        )
    }

}

/** Accept only network destinations that cannot silently downgrade to cleartext. */
fun String.isSecureBlossomUrl(): Boolean = runCatching {
    val url = Url(this)
    url.protocol.name.equals("https", ignoreCase = true) && url.host.isNotBlank()
}.getOrDefault(false)
