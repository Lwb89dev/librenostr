package net.primal.data.remote.api.gifs

import io.github.aakira.napier.Napier
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import net.primal.data.remote.api.gifs.model.GifCursor
import net.primal.data.remote.api.gifs.model.GifSearchPage
import net.primal.data.remote.api.gifs.model.GifSource

/**
 * nostr.build first, GIFverse when nostr.build cannot answer.
 *
 * nostr.build is the provider the picker is for: Nostr-native GIFs, hosted where Nostr media
 * already lives. But it only serves clients it has registered, and a GIF button that shows an
 * error until that paperwork is done is worse than one that shows GIFs from somewhere else. So:
 * - a first page goes to nostr.build unless it recently refused; on a refusal, a rate limit, a
 *   server error or a network failure the same request is answered by GIFverse instead;
 * - a refusal is remembered for [NOT_REGISTERED_PAUSE] and any other failure for
 *   [FAILURE_PAUSE], so typing a query does not cost a doomed round-trip to nostr.build per
 *   keystroke — and the moment LibreNostr is registered, nostr.build takes over again on its own,
 *   at the latest one pause later, with no update needed;
 * - later pages always go to the provider named in their cursor: an offset into one provider's
 *   list means nothing in the other's.
 */
internal class FallbackGifSearchApi(
    private val nostrBuild: NostrBuildGifApi,
    private val gifverse: GifverseGifApi,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : GifSearchApi {

    @Volatile
    private var nostrBuildPausedUntil: ComparableTimeMark? = null

    override suspend fun search(query: String, cursor: GifCursor?): GifSearchPage =
        page(
            cursor = cursor,
            fromNostrBuild = { offset -> nostrBuild.search(query = query, offset = offset) },
            fromGifverse = { offset -> gifverse.search(query = query, offset = offset) },
        )

    override suspend fun trending(cursor: GifCursor?): GifSearchPage =
        page(
            cursor = cursor,
            fromNostrBuild = { offset -> nostrBuild.trending(offset = offset) },
            fromGifverse = { offset -> gifverse.trending(offset = offset) },
        )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun suggest(query: String): List<String> {
        if (isNostrBuildPaused()) return emptyList()
        return try {
            nostrBuild.suggest(query = query)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pauseNostrBuild(error)
            emptyList()
        }
    }

    private suspend fun page(
        cursor: GifCursor?,
        fromNostrBuild: suspend (offset: Int) -> GifSearchPage,
        fromGifverse: suspend (offset: Int) -> GifSearchPage,
    ): GifSearchPage =
        when (cursor?.source) {
            GifSource.NostrBuild -> fromNostrBuild(cursor.offset)
            GifSource.Gifverse -> fromGifverse(cursor.offset)
            null -> firstPage(fromNostrBuild = fromNostrBuild, fromGifverse = fromGifverse)
        }

    /** A first page: nostr.build unless it is paused, GIFverse if it is or if it fails now. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun firstPage(
        fromNostrBuild: suspend (offset: Int) -> GifSearchPage,
        fromGifverse: suspend (offset: Int) -> GifSearchPage,
    ): GifSearchPage {
        if (isNostrBuildPaused()) return fromGifverse(0)
        return try {
            fromNostrBuild(0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pauseNostrBuild(error)
            fromGifverse(0)
        }
    }

    private fun isNostrBuildPaused(): Boolean = nostrBuildPausedUntil?.hasNotPassedNow() == true

    private fun pauseNostrBuild(error: Exception) {
        val pause: Duration = when (error) {
            is GifProviderException.NotRegistered -> NOT_REGISTERED_PAUSE
            else -> FAILURE_PAUSE
        }
        Napier.i { "nostr.build GIF search unavailable (${error.message}); using GIFverse for $pause" }
        nostrBuildPausedUntil = timeSource.markNow() + pause
    }

    companion object {
        val NOT_REGISTERED_PAUSE = 30.minutes
        val FAILURE_PAUSE = 1.minutes
    }
}
