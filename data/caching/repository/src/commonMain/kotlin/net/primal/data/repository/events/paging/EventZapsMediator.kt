package net.primal.data.repository.events.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.aakira.napier.Napier
import kotlinx.coroutines.withContext
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.events.EventZap
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.events.RelayEventZapsFetcher
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.relay.RelayEventQuerier

@ExperimentalPagingApi
class EventZapsMediator(
    private val eventId: String,
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val relayEventQuerier: RelayEventQuerier,
    private val eventTags: List<String> = listOf(eventId),
    private val addressTags: List<String> = emptyList(),
) : RemoteMediator<Int, EventZap>() {

    override suspend fun load(loadType: LoadType, state: PagingState<Int, EventZap>): MediatorResult =
        withContext(dispatcherProvider.io()) {
            try {
                RelayEventZapsFetcher(querier = relayEventQuerier, database = database).fetch(
                    eventTags = eventTags,
                    addressTags = addressTags,
                    limit = 100,
                )
                MediatorResult.Success(endOfPaginationReached = true)
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { error.message ?: "" }
                MediatorResult.Error(throwable = error)
            }
        }
}
