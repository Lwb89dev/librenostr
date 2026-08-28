package net.primal.data.repository.importer

import kotlinx.coroutines.withContext
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.notes.FeedPostDataCrossRef
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.threads.NoteConversationCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.broadcast.BroadcastApi
import net.primal.data.remote.api.importing.PrimalImportApi
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResource
import net.primal.data.remote.mapper.mapAsMapPubkeyToListOfBlossomServers
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.mappers.remote.mapAsPostDataPO
import net.primal.data.repository.mappers.remote.mapAsProfileDataPO
import net.primal.data.repository.mappers.remote.mapNotNullAsRepostDataPO
import net.primal.data.repository.mappers.remote.parseAndFoldPrimalLegendProfiles
import net.primal.data.repository.mappers.remote.parseAndFoldPrimalPremiumInfo
import net.primal.data.repository.mappers.remote.parseAndFoldPrimalUserNames
import net.primal.domain.common.PrimalEvent
import net.primal.domain.feeds.buildLatestNotesUserFeedSpec
import net.primal.domain.global.BroadcastEventResponse
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.shared.data.local.db.withTransaction

internal class CachingImportRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val importApi: PrimalImportApi,
    private val broadcastApi: BroadcastApi,
    private val invalidationTracker: FeedSpecInvalidationTracker,
) : CachingImportRepository {

    override suspend fun cacheNostrEvents(vararg events: NostrEvent) {
        cacheEvents(nostrEvents = events.toList(), primalEvents = emptyList())
    }

    override suspend fun cacheNostrEvents(events: List<NostrEvent>) {
        cacheEvents(nostrEvents = events, primalEvents = emptyList())
    }

    override suspend fun cachePrimalEvents(vararg events: PrimalEvent) {
        cacheEvents(nostrEvents = emptyList(), primalEvents = events.toList())
    }

    override suspend fun cachePrimalEvents(events: List<PrimalEvent>) {
        cacheEvents(nostrEvents = emptyList(), primalEvents = events)
    }

    override suspend fun cacheEvents(nostrEvents: List<NostrEvent>, primalEvents: List<PrimalEvent>) =
        withContext(dispatcherProvider.io()) {
            val notes = nostrEvents.filter { it.kind in LOCAL_NOTE_KINDS }
                .mapAsPostDataPO(
                    referencedPosts = emptyList(),
                    referencedArticles = emptyList(),
                    referencedHighlights = emptyList(),
                )
            val reposts = nostrEvents.filter { it.kind == NostrEventKind.ShortTextNoteRepost.value }
                .mapNotNullAsRepostDataPO()

            database.withTransaction {
                persistProfiles(nostrEvents = nostrEvents, primalEvents = primalEvents)
                if (notes.isNotEmpty()) {
                    database.posts().upsertAll(data = notes)
                    persistThreadLinks(notes)
                }
                if (reposts.isNotEmpty()) {
                    database.reposts().upsertAll(data = reposts)
                }
                persistFeedMembership(notes = notes, repostIdsByAuthor = reposts.map { it.repostId to it.authorId })
            }

            invalidateFeeds(notes = notes, repostAuthors = reposts.map { it.authorId })
        }

    private suspend fun persistProfiles(nostrEvents: List<NostrEvent>, primalEvents: List<PrimalEvent>) {
        val primalUserNames = primalEvents
            .filter { it.kind == NostrEventKind.PrimalUserNames.value }
            .parseAndFoldPrimalUserNames()
        val primalPremiumInfo = primalEvents
            .filter { it.kind == NostrEventKind.PrimalPremiumInfo.value }
            .parseAndFoldPrimalPremiumInfo()
        val primalLegendProfiles = primalEvents
            .filter { it.kind == NostrEventKind.PrimalLegendProfiles.value }
            .parseAndFoldPrimalLegendProfiles()
        val cdnResources = primalEvents
            .filter { it.kind == NostrEventKind.PrimalCdnResource.value }
            .flatMapNotNullAsCdnResource()
        val blossomServers = nostrEvents
            .filter { it.kind == NostrEventKind.BlossomServerList.value }
            .mapAsMapPubkeyToListOfBlossomServers()
        val profilesPO = nostrEvents
            .filter { it.kind == NostrEventKind.Metadata.value }
            .mapAsProfileDataPO(
                cdnResources = cdnResources,
                primalUserNames = primalUserNames,
                primalPremiumInfo = primalPremiumInfo,
                primalLegendProfiles = primalLegendProfiles,
                blossomServers = blossomServers,
            )
        if (profilesPO.isNotEmpty()) {
            database.profiles().insertOrUpdateAll(data = profilesPO)
        }
    }

    private suspend fun persistThreadLinks(notes: List<PostData>) {
        val selfLinks = notes.map { NoteConversationCrossRef(noteId = it.postId, replyNoteId = it.postId) }
        val parentLinks = notes.mapNotNull { note ->
            val parentId = note.replyToPostId ?: return@mapNotNull null
            NoteConversationCrossRef(noteId = parentId, replyNoteId = note.postId)
        }
        database.threadConversations().connectNoteWithReply(data = selfLinks + parentLinks)
    }

    private suspend fun persistFeedMembership(
        notes: List<PostData>,
        repostIdsByAuthor: List<Pair<String, String>>,
    ) {
        notes.forEach { note ->
            val specs = feedSpecsFor(
                userId = note.authorId,
                includeLatestWithoutReplies = note.replyToPostId == null,
            )
            connectToFeeds(ownerId = note.authorId, eventId = note.postId, specs = specs)
        }
        repostIdsByAuthor.forEach { (repostId, authorId) ->
            val specs = feedSpecsFor(userId = authorId, includeLatestWithoutReplies = true)
            connectToFeeds(ownerId = authorId, eventId = repostId, specs = specs)
        }
    }

    private fun feedSpecsFor(userId: String, includeLatestWithoutReplies: Boolean): List<String> {
        val specs = mutableListOf(
            FOLLOWING_NOTES_LWR_SPEC,
            buildLatestNotesUserFeedSpec(userId),
        )
        if (includeLatestWithoutReplies) specs.add(FOLLOWING_NOTES_SPEC)
        return specs
    }

    private suspend fun connectToFeeds(ownerId: String, eventId: String, specs: List<String>) {
        val positions = allocateTopPositions(count = specs.size)
        val rows = specs.mapIndexed { index, spec ->
            FeedPostDataCrossRef(
                position = positions[index],
                ownerId = ownerId,
                feedSpec = spec,
                eventId = eventId,
            )
        }
        database.feedsConnections().connect(data = rows)
    }

    private suspend fun allocateTopPositions(count: Int): List<Long> {
        var current = database.feedsConnections().findMinPosition() ?: 1L
        return List(count) {
            current -= 1
            if (current == 0L) current = -1L
            current
        }
    }

    private fun invalidateFeeds(notes: List<PostData>, repostAuthors: List<String>) {
        val owners = (notes.map { it.authorId } + repostAuthors).distinct()
        owners.forEach { ownerId ->
            feedSpecsFor(userId = ownerId, includeLatestWithoutReplies = true).forEach { spec ->
                invalidationTracker.invalidate(ownerId = ownerId, feedSpec = spec)
            }
        }
    }

    override suspend fun importEvents(events: List<NostrEvent>): Boolean =
        withContext(dispatcherProvider.io()) {
            importApi.importEvents(events)
        }

    override suspend fun broadcastEvents(
        events: List<NostrEvent>,
        relays: List<String>,
    ): Result<List<BroadcastEventResponse>> =
        withContext(dispatcherProvider.io()) {
            runCatching {
                broadcastApi.broadcastEvents(events = events, relays = relays)
                    .map { response ->
                        BroadcastEventResponse(
                            eventId = response.eventId,
                            responses = response.responses,
                        )
                    }
            }
        }

    companion object {
        private val LOCAL_NOTE_KINDS = setOf(
            NostrEventKind.ShortTextNote.value,
            NostrEventKind.Poll.value,
            NostrEventKind.ZapPoll.value,
        )
        private const val FOLLOWING_NOTES_SPEC = "{\"id\":\"latest\",\"kind\":\"notes\"}"
        private const val FOLLOWING_NOTES_LWR_SPEC =
            "{\"id\":\"latest\",\"include_replies\":true,\"kind\":\"notes\"}"
    }
}
