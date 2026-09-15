package net.primal.domain.events

import androidx.paging.PagingData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import net.primal.core.utils.Result
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.NostrEvent

interface EventRepository {
    fun pagedEventZaps(
        userId: String,
        eventId: String,
        articleATag: String?,
        zapKind: ZapKind = ZapKind.GENERIC,
    ): Flow<PagingData<EventZap>>

    suspend fun observeZapsByEventId(eventId: String, zapKind: ZapKind = ZapKind.GENERIC): Flow<List<EventZap>>

    fun observeEventStats(eventIds: List<String>): Flow<List<NostrEventStats>>
    fun observeUserEventStatus(eventIds: List<String>, userId: String): Flow<List<NostrEventUserStats>>

    /**
     * Fetches like/reply/repost/zap counters for [eventIds] from relays and persists them.
     * Deliberately does not invalidate any feed's paging source — callers that need the update
     * to be visible should observe it via [observeEventStats]/[observeUserEventStatus] instead,
     * which react to the same Room write this performs. Meant for on-demand, targeted refreshes
     * (e.g. a note becoming visible in a viewport) rather than a whole page's worth at once.
     */
    suspend fun fetchAndCacheEventStats(eventIds: List<String>, userId: String)

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchEventActions(eventId: String, kind: Int): List<NostrEventAction>

    @Throws(NetworkException::class, CancellationException::class)
    suspend fun fetchEventZaps(
        userId: String,
        eventId: String,
        limit: Int,
    )

    suspend fun fetchReplaceableEvent(naddr: Naddr): Result<Unit>

    suspend fun fetchReplaceableEvents(naddrs: List<Naddr>): Result<Unit>

    suspend fun getZapRequests(invoices: List<String>): Result<Map<String, NostrEvent>>

    suspend fun saveZapRequest(
        invoice: String,
        zapRequestEvent: NostrEvent,
        zapKind: ZapKind = ZapKind.GENERIC,
    )

    suspend fun deleteZapRequest(invoice: String)
}
