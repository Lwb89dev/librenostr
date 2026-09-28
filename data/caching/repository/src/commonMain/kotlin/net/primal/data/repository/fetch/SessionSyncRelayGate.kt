package net.primal.data.repository.fetch

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Narrows how much of the shared relay-query concurrency a background session-start sync can use.
 *
 * `RelayPool`'s own `MAX_CONCURRENT_QUERIES` gate (4, empirically tuned on-device — see its own
 * doc comment) is shared by every `query()` call in the app: whatever screen the user opens right
 * after a cold start competes for the same slots as the notification backfill and chat sync that
 * [net.primal.android.core.updater.SessionSyncCoordinator] fires at the same moment. This does not
 * raise or touch that shared cap — it gives session-start sync a second, smaller cap of its own, so
 * a background sync's internal fan-out (several interaction kinds, several backfill pages) cannot
 * by itself consume most of the 4 slots that a foreground screen also needs.
 *
 * Deliberately not derived from the same on-device measurements that picked 4 for `RelayPool` —
 * [MAX_CONCURRENT_BACKGROUND_QUERIES] needs the same kind of on-device validation before it is
 * trusted, not just a smaller number chosen by inspection.
 */
internal class SessionSyncRelayGate(maxConcurrent: Int = MAX_CONCURRENT_BACKGROUND_QUERIES) {

    private val gate = Semaphore(maxConcurrent)

    fun wrap(querier: RelayEventQuerier): RelayEventQuerier =
        object : RelayEventQuerier {
            override suspend fun query(filter: RelayFilter): List<NostrEvent> =
                gate.withPermit { querier.query(filter) }
        }

    companion object {
        const val MAX_CONCURRENT_BACKGROUND_QUERIES = 2
    }
}
