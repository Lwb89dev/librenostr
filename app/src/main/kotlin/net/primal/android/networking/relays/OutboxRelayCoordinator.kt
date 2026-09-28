package net.primal.android.networking.relays

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.UserRelays
import net.primal.android.user.repository.RelayRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching

/**
 * Widens where [RelaysSocketManager]'s shared query pool actually reads from, without changing
 * *how many* relay queries the pool runs at once.
 *
 * The pool otherwise only ever talks to the relays the user explicitly configured, regardless of
 * whether those relays carry a given followed author's content at all — a query for that
 * author's notes still pays the full quorum/timeout cost on every relay that simply never had
 * them (this showed up on-device as 65-140 "REQ timeout" log lines per feed refresh). This class
 * periodically samples the active account's follow list, batch-fetches each sampled author's
 * NIP-65 (kind 10002) declared *write* relays via [RelayRepository.fetchAndUpdateUserRelays] —
 * the same batch call the mention-tagging feature already uses safely for other users' relay
 * lists — and merges the most common ones into the pool via
 * [RelaysSocketManager.updateOutboxEnrichmentRelays].
 *
 * Deliberately NOT a dedicated relay pool per author: [RelaysSocketManager.queryEvents]'s
 * per-call throwaway-pool primitive would give each one its own independent
 * [RelayPool.MAX_CONCURRENT_QUERIES] budget, multiplying real concurrent outbound REQs across
 * relays — exactly the rejection storm fixed in v0.5.16. Enriching the one shared pool instead
 * means every query still goes through that pool's single, already-tuned concurrency gate.
 */
@Singleton
class OutboxRelayCoordinator @Inject constructor(
    dispatchers: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val relayRepository: RelayRepository,
    private val relaysSocketManager: RelaysSocketManager,
) {

    private val scope = CoroutineScope(dispatchers.io())

    init {
        scope.launch {
            activeAccountStore.activeUserId.collectLatest { userId ->
                // Cleared immediately on every account change (including a switch between two
                // logged-in accounts, not only logout) so the new account never briefly reads
                // through relays enriched for the previous one.
                relaysSocketManager.updateOutboxEnrichmentRelays(emptyList())
                if (userId.isEmpty()) return@collectLatest

                // Left idle for a beat so this doesn't compete with the very first pull-to-refresh
                // after login for the pool's own concurrency budget.
                delay(STARTUP_DELAY)
                while (true) {
                    runCatching { refreshEnrichmentRelays() }
                    delay(REFRESH_INTERVAL)
                }
            }
        }
    }

    private suspend fun refreshEnrichmentRelays() {
        val following = activeAccountStore.activeUserAccount().following
        if (following.isEmpty()) return

        val sample = following.take(MAX_AUTHORS_SAMPLED)
        val userRelays = relayRepository.fetchAndUpdateUserRelays(userIds = sample)

        relaysSocketManager.updateOutboxEnrichmentRelays(
            relays = userRelays.topWriteRelays(maxRelays = MAX_ENRICHMENT_RELAYS),
        )
    }

    companion object {
        private val STARTUP_DELAY = 20.seconds
        private val REFRESH_INTERVAL = 6.hours

        /** Relays reject very large filter arrays; a follow list can be in the thousands. */
        private const val MAX_AUTHORS_SAMPLED = 300

        /** Keeps the enriched pool small — this widens coverage, it isn't meant to replace it. */
        private const val MAX_ENRICHMENT_RELAYS = 8
    }
}

/**
 * Selects up to [maxRelays] declared write relays from [this] batch of authors' NIP-65 lists by
 * greedy set cover, always returned read-only (`write = false`) — this function decides
 * *coverage*, never a publish target. A plain top-level function (no coroutines, no
 * `RelayRepository`/`RelaysSocketManager` dependency) so the actual selection logic is directly
 * unit-testable without touching a dispatcher, a mock relay pool, or [OutboxRelayCoordinator]'s
 * own long-lived loop.
 *
 * Repeatedly picks the relay that still covers the most authors nobody selected so far covers yet,
 * instead of the relay used by the most authors overall. The two agree when one relay dominates,
 * but diverge exactly where it matters: eight authors who all happen to share both a common relay
 * and a second, almost-as-common one, plus a ninth author reachable only through a relay nobody
 * else uses, is a realistic follow-list shape — picking by raw popularity spends the whole budget
 * on the first two and never reaches the ninth author at all, while set cover picks the widely
 * shared relay once and then spends its next pick on the one relay that actually adds someone new.
 * For the same [maxRelays] budget this can only match or beat plain popularity on how many authors
 * end up reachable through *some* selected relay, which is the only thing this list is for.
 *
 * Stops as soon as no remaining relay would add anyone new, even under budget: a relay that only
 * repeats authors already covered by an earlier pick is not worth a slot, so this can return fewer
 * than [maxRelays] entries when the sample is already fully covered by fewer relays than that.
 */
internal fun List<UserRelays>.topWriteRelays(maxRelays: Int): List<Relay> {
    val authorsByRelay: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        this@topWriteRelays.forEach { userRelays ->
            userRelays.relays.filter { it.write }.forEach { relay ->
                getOrPut(relay.url) { mutableSetOf() }.add(userRelays.pubkey)
            }
        }
    }

    val covered = mutableSetOf<String>()
    val remainingRelays = authorsByRelay.keys.toMutableSet()
    val selected = mutableListOf<String>()

    while (selected.size < maxRelays) {
        // Ties are broken by URL purely so the result is deterministic and testable; nothing about
        // one relay over the other makes it a better pick once they cover the same new authors.
        val next = remainingRelays
            .associateWith { (authorsByRelay.getValue(it) - covered).size }
            .filterValues { it > 0 }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key
            ?: break

        selected += next
        covered += authorsByRelay.getValue(next)
        remainingRelays -= next
    }

    return selected.map { Relay(url = it, read = true, write = false) }
}
