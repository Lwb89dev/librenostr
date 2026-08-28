package net.primal.android.nostr.publish

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.notary.NostrNotary
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignResult
import org.junit.Test

class NostrPublisherTest {

    private val signedEvent = NostrEvent(
        id = "event-id",
        pubKey = "pubkey",
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = "sig",
    )

    private fun buildPublisher(
        relaysSocketManager: RelaysSocketManager = mockk(relaxed = true),
        nostrNotary: NostrNotary = mockk(relaxed = true) {
            coEvery { signNostrEvent(any<NostrUnsignedEvent>()) } returns SignResult.Signed(signedEvent)
        },
        cachingImportRepository: CachingImportRepository = mockk(relaxed = true),
    ) = NostrPublisher(
        relaysSocketManager = relaysSocketManager,
        nostrNotary = nostrNotary,
        cachingImportRepository = cachingImportRepository,
    )

    @Test
    fun signPublishImportNostrEvent_persistsLocallyInsteadOfCacheImport() =
        runTest {
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true)
            val publisher = buildPublisher(cachingImportRepository = cachingImportRepository)

            publisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = signedEvent.pubKey,
                    kind = signedEvent.kind,
                    content = signedEvent.content,
                ),
                outboxRelays = emptyList(),
            )

            coVerify { cachingImportRepository.cacheNostrEvents(events = listOf(signedEvent)) }
            coVerify(exactly = 0) { cachingImportRepository.importEvents(any()) }
        }
}
