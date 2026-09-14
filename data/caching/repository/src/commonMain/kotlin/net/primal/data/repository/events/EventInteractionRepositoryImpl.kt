package net.primal.data.repository.events

import io.github.aakira.napier.Napier
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.createAppBuildHelper
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.events.EventInteractionRepository
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asClientTag
import net.primal.domain.nostr.asEventIdTag
import net.primal.domain.nostr.asKindTag
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.asReplaceableEventTag
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.publisher.NostrPublishException
import net.primal.domain.publisher.PrimalPublisher

class EventInteractionRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val primalPublisher: PrimalPublisher,
    private val database: CachingDatabase,
) : EventInteractionRepository {

    private val appBuildHelper = createAppBuildHelper()

    override suspend fun likeEvent(
        userId: String,
        eventId: String,
        eventAuthorId: String,
        optionalTags: List<JsonArray>,
    ) = withContext(dispatcherProvider.io()) {
        val statsUpdater = EventStatsUpdater(
            eventId = eventId,
            userId = userId,
            eventAuthorId = eventAuthorId,
            database = database,
        )

        try {
            statsUpdater.increaseLikeStats()
            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.Reaction.value,
                    tags = listOf(
                        eventId.asEventIdTag(),
                        eventAuthorId.asPubkeyTag(),
                        appBuildHelper.getClientName().asClientTag(),
                    ) + optionalTags,
                    content = "+",
                ),
            )
        } catch (error: NostrPublishException) {
            Napier.w(error) { "Failed to publish event." }
            statsUpdater.revertStats()
            throw error
        } catch (error: SignatureException) {
            Napier.w(error) { "Unable to sign event." }
            statsUpdater.revertStats()
            throw error
        }
    }

    override suspend fun repostEvent(
        userId: String,
        eventId: String,
        eventKind: Int,
        eventAuthorId: String,
        eventRawNostrEvent: String,
        optionalTags: List<JsonArray>,
    ) = withContext(dispatcherProvider.io()) {
        val statsUpdater = EventStatsUpdater(
            eventId = eventId,
            userId = userId,
            eventAuthorId = eventAuthorId,
            database = database,
        )

        try {
            statsUpdater.increaseRepostStats()
            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = if (eventKind == NostrEventKind.ShortTextNote.value) {
                        NostrEventKind.ShortTextNoteRepost.value
                    } else {
                        NostrEventKind.GenericRepost.value
                    },
                    tags = listOf(
                        eventId.asEventIdTag(),
                        eventAuthorId.asPubkeyTag(),
                        appBuildHelper.getClientName().asClientTag(),
                    ) + optionalTags,
                    content = eventRawNostrEvent,
                ),
            )
        } catch (error: NostrPublishException) {
            Napier.w(error) { "Failed to publish event." }
            statsUpdater.revertStats()
            throw error
        } catch (error: SignatureException) {
            Napier.w(error) { "Unable to sign event." }
            statsUpdater.revertStats()
            throw error
        }
    }

    override suspend fun deleteEvent(
        userId: String,
        eventIdentifier: String,
        eventKind: NostrEventKind,
        content: String,
        relayHint: String?,
    ) = withContext(dispatcherProvider.io()) {
        primalPublisher.signPublishImportNostrEvent(
            unsignedNostrEvent = NostrUnsignedEvent(
                pubKey = userId,
                kind = NostrEventKind.EventDeletion.value,
                tags = listOf(
                    if (eventKind == NostrEventKind.LongFormContent) {
                        eventIdentifier.asReplaceableEventTag(relayHint = relayHint)
                    } else {
                        eventIdentifier.asEventIdTag(authorPubkey = userId, relayHint = relayHint)
                    },
                    eventKind.asKindTag(),
                ),
                content = content,
            ),
        )
    }

    override suspend fun deleteZaps(eventId: String) {
        withContext(dispatcherProvider.io()) {
            database.eventZaps().deleteAll(eventId = eventId)
        }
    }
}
