package net.primal.data.remote.api.gifs

import net.primal.data.remote.api.gifs.model.GifSource

/** A GIF provider answered, but not with results. */
sealed class GifProviderException(message: String) : Exception(message) {

    abstract val provider: GifSource

    /** The provider only serves registered clients and LibreNostr is not (yet) one of them. */
    class NotRegistered(override val provider: GifSource) :
        GifProviderException("$provider does not serve this client: it is not registered")

    /** Too many requests; worth retrying later, not right away. */
    class RateLimited(override val provider: GifSource) :
        GifProviderException("$provider is rate limiting this client")

    /** Any other non-success answer (5xx, an unexpected 4xx). */
    class Unavailable(override val provider: GifSource, val status: Int) :
        GifProviderException("$provider answered HTTP $status")
}
