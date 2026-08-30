package net.primal.android.wallet.nwc.handler

import kotlinx.coroutines.withContext
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.connections.nostr.handler.Nip47EventsHandler
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

class Nip47EventsHandlerImpl(
    private val dispatchers: DispatcherProvider,
    private val relayEventQuerier: RelayEventQuerier,
) : Nip47EventsHandler {
    override suspend fun fetchNip47Events(eventIds: List<String>): Result<List<NostrEvent>> =
        withContext(dispatchers.io()) {
            if (eventIds.isEmpty()) return@withContext net.primal.core.utils.Result.success(emptyList())
            runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.NwcResponse.value),
                        ids = eventIds.distinct(),
                        limit = eventIds.distinct().size,
                    ),
                ).filter { it.id in eventIds }
            }
        }
}
