package net.primal.android.nostrconnect.handler

import kotlinx.coroutines.withContext
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.account.handler.Nip46EventsHandler
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

class Nip46EventsHandlerImpl(
    private val dispatchers: DispatcherProvider,
    private val relayEventQuerier: RelayEventQuerier,
) : Nip46EventsHandler {
    override suspend fun fetchNip46Events(eventIds: List<String>): Result<List<NostrEvent>> =
        withContext(dispatchers.io()) {
            if (eventIds.isEmpty()) return@withContext net.primal.core.utils.Result.success(emptyList())
            runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.NostrConnect.value),
                        ids = eventIds.distinct(),
                        limit = eventIds.distinct().size,
                    ),
                ).filter { it.id in eventIds }
            }
        }
}
