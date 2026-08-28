package net.primal.android.nostr.publish

import io.github.aakira.napier.Napier
import javax.inject.Inject
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.user.domain.Relay
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.ContentMetadata
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.cryptography.utils.unwrapOrThrow
import net.primal.domain.publisher.PrimalPublishResult
import net.primal.domain.publisher.PrimalPublisher

class NostrPublisher @Inject constructor(
    private val relaysSocketManager: RelaysSocketManager,
    private val nostrNotary: NostrNotary,
    private val cachingImportRepository: CachingImportRepository,
) : PrimalPublisher {

    private suspend fun persistEventLocally(event: NostrEvent) {
        runCatching {
            cachingImportRepository.cacheNostrEvents(events = listOf(event))
        }.onFailure { error ->
            Napier.w(throwable = error) { "Failed to persist event ${event.id} locally." }
        }
    }

    @Throws(NostrPublishException::class)
    private suspend fun publishAndImportEvent(signedNostrEvent: NostrEvent, outboxRelays: List<String> = emptyList()) {
        relaysSocketManager.publishEvent(signedNostrEvent)
        persistEventLocally(signedNostrEvent)
        if (outboxRelays.isNotEmpty()) {
            runCatching {
                relaysSocketManager.publishEvent(
                    nostrEvent = signedNostrEvent,
                    relays = outboxRelays.map { Relay(url = it, read = false, write = true) },
                )
            }.onFailure { error ->
                Napier.w(throwable = error) { "Failed to publish to outbox relays." }
            }
        }
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    override suspend fun signPublishImportNostrEvent(
        unsignedNostrEvent: NostrUnsignedEvent,
        outboxRelays: List<String>,
    ): PrimalPublishResult {
        val signedNostrEvent = nostrNotary.signNostrEvent(unsignedNostrEvent = unsignedNostrEvent).unwrapOrThrow()
        publishAndImportEvent(signedNostrEvent = signedNostrEvent, outboxRelays = outboxRelays)
        return PrimalPublishResult(
            nostrEvent = signedNostrEvent,
        )
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun publishUserProfile(userId: String, contentMetadata: ContentMetadata): NostrEvent {
        val signedNostrEvent = nostrNotary
            .signMetadataNostrEvent(userId = userId, metadata = contentMetadata)
            .unwrapOrThrow()

        relaysSocketManager.publishEvent(nostrEvent = signedNostrEvent)
        persistEventLocally(signedNostrEvent)
        return signedNostrEvent
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun publishUserFollowList(
        userId: String,
        contacts: Set<String>,
        content: String,
    ): NostrEvent {
        val signedNostrEvent = nostrNotary.signFollowListNostrEvent(
            userId = userId,
            contacts = contacts,
            content = content,
        ).unwrapOrThrow()

        publishAndImportEvent(signedNostrEvent)
        return signedNostrEvent
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun publishRelayList(userId: String, relays: List<Relay>): NostrEvent {
        val signedNostrEvent = nostrNotary.signRelayListMetadata(userId = userId, relays = relays).unwrapOrThrow()
        relaysSocketManager.publishEvent(nostrEvent = signedNostrEvent, relays = relays)
        persistEventLocally(signedNostrEvent)
        return signedNostrEvent
    }
}
