package net.primal.data.repository.feed.processors

import net.primal.core.utils.asMapByKey
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.local.dao.events.EventUriNostr
import net.primal.data.local.dao.events.eventRelayHintsUpserter
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.dao.threads.ArticleCommentCrossRef
import net.primal.data.local.dao.threads.NoteConversationCrossRef
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResource
import net.primal.data.remote.mapper.flatMapNotNullAsCdnResourcesAndThumbnails
import net.primal.data.remote.mapper.flatMapNotNullAsLinkPreviewResource
import net.primal.data.remote.mapper.mapAsMapPubkeyToListOfBlossomServers
import net.primal.data.repository.mappers.remote.applyPollStats
import net.primal.data.repository.mappers.remote.flatMapAsEventHintsPO
import net.primal.data.repository.mappers.remote.flatMapPostsAsEventUriPO
import net.primal.data.repository.mappers.remote.flatMapPostsAsReferencedNostrUriDO
import net.primal.data.repository.mappers.remote.mapAsEventZapDO
import net.primal.data.repository.mappers.remote.mapAsPollResponseVotes
import net.primal.data.repository.mappers.remote.mapAsPostDataPO
import net.primal.data.repository.mappers.remote.mapAsProfileDataPO
import net.primal.data.repository.mappers.remote.mapAsReferencedNostrUriDO
import net.primal.data.repository.mappers.remote.mapAsZapPollVotes
import net.primal.data.repository.mappers.remote.mapNotNullAsArticleDataPO
import net.primal.data.repository.mappers.remote.mapNotNullAsEventStatsPO
import net.primal.data.repository.mappers.remote.mapNotNullAsEventUserStatsPO
import net.primal.data.repository.mappers.remote.mapNotNullAsPollDataPO
import net.primal.data.repository.mappers.remote.mapNotNullAsPostDataPO
import net.primal.data.repository.mappers.remote.mapNotNullAsRepostDataPO
import net.primal.data.repository.mappers.remote.mapNotNullAsStreamDataPO
import net.primal.data.repository.mappers.remote.mapReferencedEventsAsArticleDataPO
import net.primal.data.repository.mappers.remote.mapReferencedEventsAsHighlightDataPO
import net.primal.data.repository.mappers.remote.mapReferencedNostrUriAsEventUriNostrPO
import net.primal.data.repository.mappers.remote.parseAndMapPrimalLegendProfiles
import net.primal.data.repository.mappers.remote.parseAndMapPrimalPollStats
import net.primal.data.repository.mappers.remote.parseAndMapPrimalPremiumInfo
import net.primal.data.repository.mappers.remote.parseAndMapPrimalUserNames
import net.primal.domain.links.CdnResource
import net.primal.domain.links.EventLinkPreviewData
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.shared.data.local.db.withTransaction

internal suspend fun FeedResponse.persistToDatabaseAsTransaction(userId: String, database: CachingDatabase) {
    database.withTransaction {
        persistToDatabase(userId = userId, database = database)
    }
}

internal suspend inline fun FeedResponse.persistToDatabase(userId: String, database: CachingDatabase) {
    val cdnResourcesAndThumbnails = this.cdnResources.flatMapNotNullAsCdnResourcesAndThumbnails()
    val cdnResources = cdnResourcesAndThumbnails.cdnResources.asMapByKey { it.url }
    val videoThumbnails = cdnResourcesAndThumbnails.videoThumbnails
    val linkPreviews = primalLinkPreviews.flatMapNotNullAsLinkPreviewResource().asMapByKey { it.url }
    val eventHints = this.primalRelayHints.flatMapAsEventHintsPO()

    val articles = this.articles.mapNotNullAsArticleDataPO(cdnResources = cdnResources)
    val referencedArticles = this.referencedEvents.mapReferencedEventsAsArticleDataPO(cdnResources = cdnResources)
    val referencedHighlights = this.referencedEvents.mapReferencedEventsAsHighlightDataPO()
    val allArticles = articles + referencedArticles

    val referencedPosts = referencedEvents.mapNotNullAsPostDataPO(
        referencedArticles = allArticles,
        referencedHighlights = referencedHighlights,
    )
    val feedPosts = (notes + polls).mapAsPostDataPO(
        referencedPosts = referencedPosts,
        referencedArticles = allArticles,
        referencedHighlights = referencedHighlights,
    )

    val primalUserNames = this.primalUserNames.parseAndMapPrimalUserNames()
    val primalPremiumInfo = this.primalPremiumInfo.parseAndMapPrimalPremiumInfo()
    val primalLegendProfiles = this.primalLegendProfiles.parseAndMapPrimalLegendProfiles()
    val existingPrimalLegendProfiles = database.profiles()
        .findLegendProfileData(profileIds = this.metadata.map { it.pubKey })
        .mapNotNull { it.value?.legendProfile?.let { value -> it.key to value } }
        .toMap()

    val blossomServers = this.blossomServers.mapAsMapPubkeyToListOfBlossomServers()

    val profiles = metadata.mapAsProfileDataPO(
        cdnResourcesMap = cdnResources,
        primalUserNames = primalUserNames,
        primalPremiumInfo = primalPremiumInfo,
        primalLegendProfiles = primalLegendProfiles + existingPrimalLegendProfiles,
        blossomServers = blossomServers,
    )
    val profileIdToProfileDataMap = profiles.asMapByKey { it.ownerId }
    val eventIdMap = profileIdToProfileDataMap.mapValues { it.value.eventId }

    val allPosts = (referencedPosts + feedPosts).map { postData ->
        postData.copy(authorMetadataId = eventIdMap[postData.authorId])
    }

    val noteAttachments = allPosts.flatMapPostsAsEventUriPO(
        cdnResources = cdnResources,
        linkPreviews = linkPreviews,
        videoThumbnails = videoThumbnails,
    )

    val refEvents = referencedEvents.mapNotNull { it.content.decodeFromJsonStringOrNull<NostrEvent>() }
    val streamData = liveActivity.mapNotNullAsStreamDataPO() + refEvents.mapNotNullAsStreamDataPO()

    val pollStatsMap = this.primalPollStats.parseAndMapPrimalPollStats()
    val allPollData = (this.polls + refEvents).mapNotNullAsPollDataPO()
    val pollDataWithStats = allPollData.filter { it.postId in pollStatsMap }.applyPollStats(pollStatsMap)
    val pollDataWithoutStats = allPollData.filter { it.postId !in pollStatsMap }
    val pollData = pollDataWithStats + pollDataWithoutStats
    val pollVotes = this.pollResponses.mapAsPollResponseVotes() + this.zaps.mapAsZapPollVotes()

    val eventZaps = zaps.mapAsEventZapDO(profilesMap = profiles.associateBy { it.ownerId })
    val reposts = reposts.mapNotNullAsRepostDataPO()
    val postStats = primalEventStats.mapNotNullAsEventStatsPO()
    val userPostStats = primalEventUserStats.mapNotNullAsEventUserStatsPO(userId = userId)

    val userVotedOptionMap = userPostStats
        .filter { it.votedForOption != null }
        .associate { it.eventId to it.votedForOption }

    val postIdToPostDataMap = allPosts.associateBy { it.postId }
    val noteNostrUris = allPosts.flatMapPostsAsReferencedNostrUriDO(
        eventIdToNostrEvent = refEvents.associateBy { it.id },
        postIdToPostDataMap = postIdToPostDataMap,
        articleIdToArticle = allArticles.associateBy { it.articleId },
        streamIdToStreamData = streamData.associateBy { it.dTag },
        profileIdToProfileDataMap = profileIdToProfileDataMap,
        cdnResources = cdnResources,
        videoThumbnails = videoThumbnails,
        linkPreviews = linkPreviews,
        postIdToPollDataMap = pollData.associateBy { it.postId },
        postIdToUserVotedOption = userVotedOptionMap,
    ).mapReferencedNostrUriAsEventUriNostrPO()

    database.profiles().insertOrUpdateAll(data = profiles)
    database.posts().upsertAll(data = allPosts)
    database.polls().upsertAll(data = pollDataWithStats)
    database.polls().insertAllOrIgnore(data = pollDataWithoutStats)
    database.pollVotes().upsertAll(data = pollVotes)
    database.eventUris().upsertAllEventUris(data = noteAttachments)
    database.eventUris().upsertAllEventNostrUris(data = noteNostrUris)
    // Heals every OTHER already-stored "not found" citation of the posts persisted just above,
    // wherever it lives — not only the ones belonging to this response. See the DAO query's own
    // doc for why that repair never happens on its own otherwise.
    database.reclassifyResolvedNoteCitations(
        postIdToPostDataMap = postIdToPostDataMap,
        profileIdToProfileDataMap = profileIdToProfileDataMap,
        cdnResources = cdnResources,
        linkPreviews = linkPreviews,
        videoThumbnails = videoThumbnails,
    )
    database.reposts().upsertAll(data = reposts)
    database.eventZaps().upsertAll(data = eventZaps)
    database.eventStats().upsertAll(data = postStats)
    database.eventUserStats().upsertAll(data = userPostStats)
    database.articles().upsertAll(list = allArticles)
    database.highlights().upsertAll(data = referencedHighlights)
    database.streams().upsertStreamData(data = streamData)
    // See NoteConversationCrossRef's own doc: this is the one-hop, genuinely-structural half of
    // the two write paths that feed it. Do not remove without also revisiting
    // persistNoteRepliesAndArticleCommentsToDatabase below — the two exist for different reasons.
    database.threadConversations().connectNoteWithReply(
        data = allPosts.map {
            NoteConversationCrossRef(
                noteId = it.postId,
                replyNoteId = it.postId,
            )
        },
    )
    database.threadConversations().connectNoteWithReply(
        data = allPosts.mapNotNull {
            NoteConversationCrossRef(
                noteId = it.replyToPostId ?: return@mapNotNull null,
                replyNoteId = it.postId,
            )
        },
    )

    val eventHintsDao = database.eventHints()
    val hintsMap = eventHints.associateBy { it.eventId }
    eventRelayHintsUpserter(dao = eventHintsDao, eventIds = eventHints.map { it.eventId }) {
        copy(relays = hintsMap[this.eventId]?.relays ?: emptyList())
    }
}

/**
 * Re-derives every stored "not found" note citation whose target is now in [postIdToPostDataMap],
 * across the whole local database — not just whatever citing note this same batch happens to also
 * contain.
 *
 * [EventUriDao.findEventUrisByType]'s own doc explains why a citation never heals on its own: the
 * classification on an [EventUriNostr] row is a one-time snapshot, not a live join, so a quote
 * fetched successfully here does nothing for a *different*, already-persisted note that quoted the
 * same thing earlier and is sitting nowhere in this particular response. Retrying a single
 * "Mentioned event not found" card was exactly that case — the retry fetched and stored the target
 * correctly, but the citing note's own row, from whenever it was first seen, was never told.
 *
 * [row.copy][EventUriNostr.copy] keeps the row's original `position` untouched, so a note whose
 * other embeds already resolved does not have this one jump to the end of its content when it
 * catches up later.
 */
private suspend fun CachingDatabase.reclassifyResolvedNoteCitations(
    postIdToPostDataMap: Map<String, PostData>,
    profileIdToProfileDataMap: Map<String, ProfileData>,
    cdnResources: Map<String, CdnResource>,
    linkPreviews: Map<String, EventLinkPreviewData>,
    videoThumbnails: Map<String, String>,
) {
    val pendingRows = eventUris().findEventUrisByType()
    val toRetry = pendingRows.filter { it.uri.extractNoteId() in postIdToPostDataMap }
    if (toRetry.isEmpty()) return

    // A quoted note's author is usually already known from somewhere else entirely — this response
    // may not carry their metadata at all, and waiting for it to happen to show up in some other
    // fetch would leave an otherwise-resolvable quote stuck for no real reason.
    val missingAuthorIds = toRetry
        .mapNotNull { row -> row.uri.extractNoteId()?.let { postIdToPostDataMap[it]?.authorId } }
        .filterNot { it in profileIdToProfileDataMap }
        .distinct()
    val knownProfiles = if (missingAuthorIds.isEmpty()) {
        profileIdToProfileDataMap
    } else {
        profileIdToProfileDataMap + profiles().findProfileData(missingAuthorIds).associateBy { it.ownerId }
    }

    val healed = toRetry.mapNotNull { row ->
        val reference = listOf(row.uri).mapAsReferencedNostrUriDO(
            eventId = row.eventId,
            eventIdToNostrEvent = emptyMap(),
            postIdToPostDataMap = postIdToPostDataMap,
            articleIdToArticle = emptyMap(),
            streamIdToStreamData = emptyMap(),
            profileIdToProfileDataMap = knownProfiles,
            cdnResources = cdnResources,
            linkPreviews = linkPreviews,
            videoThumbnails = videoThumbnails,
        ).singleOrNull() ?: return@mapNotNull null
        if (reference.type == EventUriNostrType.Unsupported) return@mapNotNull null
        row.copy(
            type = reference.type,
            referencedEventAlt = reference.referencedEventAlt,
            referencedNote = reference.referencedNote,
        )
    }
    if (healed.isNotEmpty()) eventUris().upsertAllEventNostrUris(data = healed)
}

internal suspend fun FeedResponse.persistNoteRepliesAndArticleCommentsToDatabase(
    noteId: String,
    database: CachingDatabase,
) {
    val cdnResources = this.cdnResources.flatMapNotNullAsCdnResource().asMapByKey { it.url }
    val articles = this.articles.mapNotNullAsArticleDataPO(cdnResources = cdnResources)
    val conversationIds = (notes + polls).map { it.id }.distinct()

    database.withTransaction {
        if (conversationIds.isNotEmpty()) {
            // Deliberately flat: every post fetched for THIS thread-open is marked as belonging
            // to noteId's conversation regardless of its actual depth. See NoteConversationCrossRef's
            // doc — ThreadConversationDao.observeNoteConversation is a single join, not recursive,
            // so this is what makes anything nested more than one level deep show up at all. Real
            // depth is reconstructed separately, client-side, from each post's own tags.
            database.threadConversations().connectNoteWithReply(
                data = conversationIds.map { eventId ->
                    NoteConversationCrossRef(
                        noteId = noteId,
                        replyNoteId = eventId,
                    )
                },
            )
        }
        database.threadConversations().connectArticleWithComment(
            data = articles.map { article ->
                ArticleCommentCrossRef(
                    articleId = article.articleId,
                    articleAuthorId = article.authorId,
                    commentNoteId = noteId,
                )
            },
        )
    }
}
