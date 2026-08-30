package net.primal.data.repository.events

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.primal.core.utils.CurrencyConversionUtils.toBigDecimal
import net.primal.core.utils.CurrencyConversionUtils.toBtc
import net.primal.core.utils.Result
import net.primal.core.utils.asMapByKey
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.map
import net.primal.core.utils.recover
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.core.utils.toDouble
import net.primal.data.local.dao.events.EventZap as EventZapPO
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.events.paging.EventZapsMediator
import net.primal.data.repository.mappers.local.asEventZapDO
import net.primal.data.repository.mappers.local.asNostrEventStats
import net.primal.data.repository.mappers.local.asNostrEventUserStats
import net.primal.data.repository.mappers.local.asProfileDataDO
import net.primal.data.repository.mappers.remote.extractZapRequestOrNull
import net.primal.data.repository.mappers.remote.mapAsEventZapDO
import net.primal.data.repository.mappers.remote.asProfileDataPOFromRelay
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.data.repository.mappers.remote.mapNotNullAsArticleDataPO
import net.primal.data.repository.mappers.remote.mapNotNullAsStreamDataPO
import net.primal.domain.events.EventRepository
import net.primal.domain.events.EventZap as EventZapDO
import net.primal.domain.events.NostrEventAction
import net.primal.domain.events.ZapKind
import net.primal.domain.links.CdnResource
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstATag
import net.primal.domain.nostr.findFirstBolt11
import net.primal.domain.nostr.findFirstEventId
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.findFirstProfileId
import net.primal.domain.nostr.findFirstZapAmount
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.LnInvoiceUtils
import net.primal.shared.data.local.db.withTransaction

class EventRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val relayEventQuerier: RelayEventQuerier,
) : EventRepository {

    override fun observeEventStats(eventIds: List<String>) =
        database.eventStats().observeStats(eventIds).map { it.map { it.asNostrEventStats() } }

    override fun observeUserEventStatus(eventIds: List<String>, userId: String) =
        database.eventUserStats().observeStats(eventIds, userId).map { it.map { it.asNostrEventUserStats() } }

    override suspend fun fetchEventActions(eventId: String, kind: Int): List<NostrEventAction> =
        withContext(dispatcherProvider.io()) {
            // Reactions (kind 7) and reposts (kind 6) are regular Nostr events.
            // Querying relays keeps this interaction view independent from Primal's
            // ranking endpoint; relay events do not carry a central score.
            val actions = runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(kind),
                        eventTags = listOf(eventId),
                        limit = 100,
                    ),
                )
            }.getOrDefault(emptyList()).distinctBy { it.id }

            if (actions.isEmpty()) return@withContext emptyList()

            val profiles = runCatching {
                relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Metadata.value),
                        authors = actions.map { it.pubKey }.distinct(),
                        limit = actions.map { it.pubKey }.distinct().size,
                    ),
                )
            }.getOrDefault(emptyList())
                .latestMetadataByPubkey()
                .map { it.asProfileDataPOFromRelay() }

            database.withTransaction {
                database.profiles().insertOrUpdateAll(data = profiles)
            }

            val profilesMap = profiles.asMapByKey { it.ownerId }
            actions.mapNotNull { action ->
                profilesMap[action.pubKey]?.let { profileData ->
                    NostrEventAction(
                        profile = profileData.asProfileDataDO(),
                        score = 0f,
                        actionEventData = action,
                        actionEventKind = action.kind,
                    )
                }
            }.distinctBy { it.profile }
        }

    override suspend fun fetchEventZaps(
        userId: String,
        eventId: String,
        limit: Int,
    ) = withContext(dispatcherProvider.io()) {
        RelayEventZapsFetcher(querier = relayEventQuerier, database = database).fetch(
            eventTags = listOf(eventId),
            limit = limit,
        )
        Unit
    }

    override fun pagedEventZaps(
        userId: String,
        eventId: String,
        articleATag: String?,
        zapKind: ZapKind,
    ): Flow<PagingData<EventZapDO>> {
        return createPager(eventId = eventId, articleATag = articleATag) {
            database.eventZaps().pagedEventZaps(eventId = articleATag ?: eventId, zapKind = zapKind)
        }.flow.map { it.map { it.asEventZapDO() } }
            .flowOn(dispatcherProvider.io())
    }

    override suspend fun observeZapsByEventId(eventId: String, zapKind: ZapKind): Flow<List<EventZapDO>> =
        withContext(dispatcherProvider.io()) {
            database.eventZaps().observeAllByEventId(eventId = eventId, zapKind = zapKind)
                .distinctUntilChanged()
                .map { list -> list.map { it.asEventZapDO() } }
        }

    override suspend fun fetchReplaceableEvent(naddr: Naddr): Result<Unit> =
        withContext(dispatcherProvider.io()) {
            runCatching {
                val event = queryRelayReplaceableEvents(
                    querier = relayEventQuerier,
                    naddrs = listOf(naddr),
                ).firstOrNull()
                persistRelayReplaceableEvents(events = listOfNotNull(event))
            }
        }

    override suspend fun fetchReplaceableEvents(naddrs: List<Naddr>): Result<Unit> =
        withContext(dispatcherProvider.io()) {
            runCatching {
                val events = queryRelayReplaceableEvents(querier = relayEventQuerier, naddrs = naddrs)
                persistRelayReplaceableEvents(events = events)
            }
        }

    private suspend fun queryRelayReplaceableEvents(
        querier: RelayEventQuerier,
        naddrs: List<Naddr>,
    ): List<NostrEvent> {
        if (naddrs.isEmpty()) return emptyList()

        return naddrs.distinct()
            .groupBy { it.kind }
            .flatMap { (kind, requested) ->
                requested.chunked(REPLACEABLE_AUTHOR_CHUNK).flatMap { chunk ->
                    val authors = chunk.map { it.userId }.distinct()
                    val candidates = querier.query(
                        RelayFilter(
                            kinds = listOf(kind),
                            authors = authors,
                            limit = (chunk.size * REPLACEABLE_EVENTS_PER_ADDRESS).coerceAtMost(500),
                        ),
                    )
                    chunk.mapNotNull { naddr ->
                        candidates.asSequence()
                            .filter { event ->
                                event.pubKey == naddr.userId &&
                                    (naddr.identifier.isEmpty() || event.tags.findFirstIdentifier() == naddr.identifier)
                            }
                            .maxByOrNull { it.createdAt }
                    }
                }
            }
            .distinctBy { it.id }
    }

    private suspend fun persistRelayReplaceableEvents(events: List<NostrEvent>) {
        if (events.isEmpty()) return

        val profiles = events
            .filter { it.kind == NostrEventKind.Metadata.value }
            .latestMetadataByPubkey()
            .map { it.asProfileDataPOFromRelay() }
        val articles = events
            .filter { it.kind == NostrEventKind.LongFormContent.value }
            .mapNotNullAsArticleDataPO(cdnResources = emptyMap<String, CdnResource>())
        val streams = events
            .filter { it.kind == NostrEventKind.LiveActivity.value }
            .mapNotNullAsStreamDataPO()

        database.withTransaction {
            database.profiles().insertOrUpdateAll(data = profiles)
            database.articles().upsertAll(articles)
            database.streams().upsertStreamData(streams)
        }
    }

    override suspend fun getZapRequests(invoices: List<String>): Result<Map<String, NostrEvent>> =
        withContext(dispatcherProvider.io()) {
            if (invoices.isEmpty()) return@withContext Result.success(emptyMap())

            val localZapReceipts = database.eventZaps().findAllByInvoices(invoices = invoices)
            val localMap = localZapReceipts.mapNotNull { zapEvent ->
                val invoice = zapEvent.invoice
                val nostrEvent = zapEvent.rawNostrEvent?.decodeFromJsonStringOrNull<NostrEvent>()
                val zapRequest = if (nostrEvent?.kind == NostrEventKind.Zap.value) {
                    nostrEvent.extractZapRequestOrNull()
                } else {
                    nostrEvent
                }
                if (invoice != null && zapRequest != null) invoice to zapRequest else null
            }.toMap()

            val missingZapReceiptsByInvoice = invoices.toSet() - localZapReceipts.mapNotNull { it.invoice }.toSet()

            if (missingZapReceiptsByInvoice.isEmpty()) return@withContext Result.success(localMap)

            runCatching {
                // NIP-57 receipts carry the paid invoice in a `bolt11` tag. Relay
                // tag filtering avoids the old invoice-to-receipt cache endpoint.
                val receipts = relayEventQuerier.query(
                    RelayFilter(
                        kinds = listOf(NostrEventKind.Zap.value),
                        bolt11Tags = missingZapReceiptsByInvoice.toList(),
                        limit = missingZapReceiptsByInvoice.size.coerceAtMost(500),
                    ),
                ).distinctBy { it.id }
                val receiptsByInvoice = receipts.mapNotNull { receipt ->
                    receipt.tags.findFirstBolt11()
                        ?.takeIf { it in missingZapReceiptsByInvoice }
                        ?.let { it to receipt }
                }.toMap()
                database.eventZaps().upsertAll(
                    data = receiptsByInvoice.values.toList().mapAsEventZapDO(profilesMap = emptyMap()),
                )
                receiptsByInvoice.mapNotNull { (invoice, receipt) ->
                    receipt.extractZapRequestOrNull()?.let { invoice to it }
                }.toMap() + localMap
            }.recover { localMap }
        }

    override suspend fun saveZapRequest(
        invoice: String,
        zapRequestEvent: NostrEvent,
        zapKind: ZapKind,
    ) = withContext(dispatcherProvider.io()) {
        val senderId = zapRequestEvent.pubKey
        val receiverId = zapRequestEvent.tags.findFirstProfileId() ?: return@withContext
        val eventId = zapRequestEvent.tags.findFirstATag()
            ?: zapRequestEvent.tags.findFirstEventId()
            ?: receiverId

        val amountInSats = zapRequestEvent.tags.findFirstZapAmount()?.toBigDecimal()
            ?: LnInvoiceUtils.getAmountInSatsOrNull(invoice)
            ?: return@withContext

        val zapSender = database.profiles().findProfileData(profileId = senderId)
        val data = EventZapPO(
            eventId = eventId,
            zapSenderId = senderId,
            zapReceiverId = receiverId,
            zapRequestAt = zapRequestEvent.createdAt,
            zapReceiptAt = 0,
            amountInBtc = amountInSats.toBtc().toDouble(),
            message = zapRequestEvent.content,
            invoice = invoice,
            rawNostrEvent = zapRequestEvent.encodeToJsonString(),
            zapSenderAvatarCdnImage = zapSender?.avatarCdnImage,
            zapSenderHandle = zapSender?.handle,
            zapSenderDisplayName = zapSender?.displayName,
            zapSenderInternetIdentifier = zapSender?.internetIdentifier,
            zapSenderPrimalLegendProfile = zapSender?.primalPremiumInfo?.legendProfile,
            zapKind = zapKind,
        )
        database.eventZaps().upsertAll(data = listOf(data))
    }

    override suspend fun deleteZapRequest(invoice: String) =
        withContext(dispatcherProvider.io()) {
            database.eventZaps().deleteByInvoice(invoice = invoice)
        }

    @OptIn(ExperimentalPagingApi::class)
    private fun createPager(
        eventId: String,
        articleATag: String?,
        pagingSourceFactory: () -> PagingSource<Int, EventZapPO>,
    ) = Pager(
        config = PagingConfig(
            pageSize = 50,
            prefetchDistance = 50,
            initialLoadSize = 150,
            enablePlaceholders = true,
        ),
        remoteMediator = EventZapsMediator(
            eventId = eventId,
            dispatcherProvider = dispatcherProvider,
            database = database,
            relayEventQuerier = relayEventQuerier,
            eventTags = listOf(eventId),
            addressTags = listOfNotNull(articleATag),
        ),
        pagingSourceFactory = pagingSourceFactory,
    )

    private companion object {
        const val REPLACEABLE_AUTHOR_CHUNK = 50
        const val REPLACEABLE_EVENTS_PER_ADDRESS = 10
    }
}
