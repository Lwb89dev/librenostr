package net.primal.android.networking.relays

import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.NostrSocketClient
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.testing.CoroutinesTestRule
import net.primal.core.utils.Result
import net.primal.domain.global.BroadcastEventResponse
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.NostrEvent
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolTest {

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    private fun buildNostrEvent(eventId: String): NostrEvent {
        return NostrEvent(
            id = eventId,
            pubKey = "01234",
            kind = 0,
            content = "",
            createdAt = System.currentTimeMillis() / 1_000,
            sig = "sig",
        )
    }

    private fun buildRelayPool(
        nostrSocketClientFactory: NostrSocketClientFactory = mockk(relaxed = true),
        cachingImportRepository: CachingImportRepository = mockk(relaxed = true),
    ) = RelayPool(
        dispatchers = coroutinesTestRule.dispatcherProvider,
        nostrSocketClientFactory = nostrSocketClientFactory,
        cachingImportRepository = cachingImportRepository,
    )

    private fun buildSocketClientReturningOkMessageSuccessFalse(
        scope: CoroutineScope,
        eventId: String,
    ): NostrSocketClient {
        return mockk<NostrSocketClient>(relaxed = true) {
            every { incomingMessages } returns flowOf(
                NostrIncomingMessage.OkMessage(eventId = eventId, success = false),
            ).shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
            )
            every { connectionGeneration } returns MutableStateFlow(0L)
        }
    }

    private fun buildSocketClientReturningOkMessageSuccessTrue(
        scope: CoroutineScope,
        eventId: String,
    ): NostrSocketClient {
        return mockk<NostrSocketClient>(relaxed = true) {
            every { incomingMessages } returns flowOf(
                NostrIncomingMessage.OkMessage(eventId = eventId, success = true),
            ).shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
            )
            every { connectionGeneration } returns MutableStateFlow(0L)
        }
    }

    private fun buildSocketClientReturningNoticeMessage(scope: CoroutineScope): NostrSocketClient {
        return mockk<NostrSocketClient>(relaxed = true) {
            every { incomingMessages } returns flowOf(
                NostrIncomingMessage.NoticeMessage(message = "This is your notice!"),
            ).shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
            )
            every { connectionGeneration } returns MutableStateFlow(0L)
        }
    }

    private fun buildSocketClientNotReturningAnything(scope: CoroutineScope): NostrSocketClient {
        return mockk<NostrSocketClient>(relaxed = true) {
            every { incomingMessages } returns emptyFlow<NostrIncomingMessage>().shareIn(
                scope = scope,
                started = SharingStarted.Lazily,
            )
            every { connectionGeneration } returns MutableStateFlow(0L)
        }
    }

    @Test(expected = NostrPublishException::class)
    fun publishEvent_throwsIfAllPublishesFail() =
        runTest {
            val relayPool = buildRelayPool()
            val eventId = "randomThrowId"
            val nostrEvent = buildNostrEvent(eventId = eventId)

            relayPool.socketClients = listOf(
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
            )

            relayPool.publishEvent(nostrEvent)
        }

    @Test
    fun publishEvent_doesNotThrowIfAtLeastOnePublishIsSuccessful() =
        runTest {
            val relayPool = buildRelayPool()
            val eventId = "randomSuccessId"
            val nostrEvent = buildNostrEvent(eventId = eventId)

            relayPool.socketClients = listOf(
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessTrue(scope = this, eventId = eventId),
            )

            relayPool.publishEvent(nostrEvent)
        }

    @Test
    fun publishEvent_doesNotThrowIfWeHaveSuccessPublishAndSomeRelaysReturnNoticeMessage() =
        runTest {
            val relayPool = buildRelayPool()
            val eventId = "randomSuccessId2"
            val nostrEvent = buildNostrEvent(eventId = eventId)

            relayPool.socketClients = listOf(
                buildSocketClientReturningNoticeMessage(scope = this),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessTrue(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
            )

            relayPool.publishEvent(nostrEvent)
        }

    @Test
    fun publishEvent_throwsImmediatelyIfAllPublishesFail() =
        runTest {
            val relayPool = buildRelayPool()
            val eventId = "randomThrowId"
            val nostrEvent = buildNostrEvent(eventId = eventId)

            relayPool.socketClients = listOf(
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
            )

            val startTime = testScheduler.currentTime
            try {
                relayPool.publishEvent(nostrEvent)
            } catch (_: NostrPublishException) {
            }
            val endTime = testScheduler.currentTime

            endTime - startTime shouldBe 0
        }

    @Test
    fun publishEvent_timeoutsAfterSomeTimeIfWeAreStillWaitingAndNoSuccessfulMessages() =
        runTest {
            val relayPool = buildRelayPool()
            val eventId = "randomTimeoutId"
            val nostrEvent = buildNostrEvent(eventId = eventId)

            relayPool.socketClients = listOf(
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientReturningOkMessageSuccessFalse(scope = this, eventId = eventId),
                buildSocketClientNotReturningAnything(scope = this),
            )

            val startTime = testScheduler.currentTime
            try {
                relayPool.publishEvent(nostrEvent)
            } catch (_: NostrPublishException) {
            }
            val endTime = testScheduler.currentTime

            endTime - startTime shouldBe RelayPool.PUBLISH_TIMEOUT
        }

    @Test
    fun publishEvent_reSendsEventOnSocketRebuildAndSucceeds() =
        runTest {
            val relayPool = buildRelayPool()
            val eventId = "rebuildReplayId"
            val nostrEvent = buildNostrEvent(eventId = eventId)

            val incoming = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 64)
            val generation = MutableStateFlow(1L)
            val socketClient = mockk<NostrSocketClient>(relaxed = true) {
                every { incomingMessages } returns incoming
                every { connectionGeneration } returns generation
            }
            relayPool.socketClients = listOf(socketClient)

            val publishJob = launch { relayPool.publishEvent(nostrEvent) }

            // runCurrent (not advanceUntilIdle) so virtual time never reaches the publish timeout.
            // First generation: the EVENT is sent once, the collector is attached, relay stays silent.
            runCurrent()
            coVerify(exactly = 1) { socketClient.sendEVENT(any()) }
            publishJob.isActive shouldBe true

            // A staleness rebuild bumps the generation; the EVENT must be re-sent on the fresh socket.
            generation.value = 2L
            runCurrent()
            coVerify(exactly = 2) { socketClient.sendEVENT(any()) }

            // The OK arrives on the rebuilt socket and the publish completes without throwing.
            incoming.emit(NostrIncomingMessage.OkMessage(eventId = eventId, success = true))
            runCurrent()
            publishJob.isCompleted shouldBe true
        }

    @Test
    fun publishEvent_ifCachingProxyEnabled_socketClientsAreNotUsed() =
        runTest {
            val eventId = "helloProxy"
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true) {
                coEvery { broadcastEvents(any(), any()) } returns Result.success(
                    listOf(
                        BroadcastEventResponse(
                            eventId = eventId,
                            responses = listOf(
                                listOf("wss://relay.example.com", """["OK","$eventId",true,""]"""),
                            ),
                        ),
                    ),
                )
            }
            val relayPool = buildRelayPool(cachingImportRepository = cachingImportRepository).apply {
                socketClients = listOf(
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )
            }

            relayPool.publishEvent(
                nostrEvent = buildNostrEvent(eventId = eventId),
                cachingProxyEnabled = true,
            )
            advanceUntilIdle()

            coVerify {
                relayPool.socketClients.forEach { socketClient ->
                    socketClient wasNot Called
                }
            }
        }

    @Test(expected = NostrPublishException::class)
    fun publishEvent_ifCachingProxyEnabled_throwsExceptionIfBroadcastResultIsNotFound() =
        runTest {
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true) {
                coEvery { broadcastEvents(any(), any()) } returns Result.failure(Exception("Not found"))
            }
            val relayPool = buildRelayPool(cachingImportRepository = cachingImportRepository).apply {
                socketClients = listOf(
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )
            }

            relayPool.publishEvent(
                nostrEvent = buildNostrEvent(eventId = "helloProxy"),
                cachingProxyEnabled = true,
            )
        }

    @Test(expected = NostrPublishException::class)
    fun publishEvent_ifCachingProxyEnabled_throwsExceptionIfBroadcastResultDoesNotHaveOKMessage() =
        runTest {
            val eventId = "eventId"
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true) {
                coEvery { broadcastEvents(any(), any()) } returns Result.success(
                    listOf(
                        BroadcastEventResponse(
                            eventId = eventId,
                            responses = listOf(
                                listOf("wss://relay.example.com", """["NOTICE","some notice"]"""),
                            ),
                        ),
                    ),
                )
            }
            val relayPool = buildRelayPool(cachingImportRepository = cachingImportRepository).apply {
                socketClients = listOf(
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )
            }

            relayPool.publishEvent(
                nostrEvent = buildNostrEvent(eventId = eventId),
                cachingProxyEnabled = true,
            )
        }

    @Test(expected = NostrPublishException::class)
    fun publishEvent_ifCachingProxyEnabled_throwsExceptionIfBroadcastResultHasOKMessageButSuccessIsFalse() =
        runTest {
            val eventId = "eventId"
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true) {
                coEvery { broadcastEvents(any(), any()) } returns Result.success(
                    listOf(
                        BroadcastEventResponse(
                            eventId = eventId,
                            responses = listOf(
                                listOf("wss://relay.example.com", """["OK","$eventId",false,"error"]"""),
                            ),
                        ),
                    ),
                )
            }
            val relayPool = buildRelayPool(cachingImportRepository = cachingImportRepository).apply {
                socketClients = listOf(
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )
            }

            relayPool.publishEvent(
                nostrEvent = buildNostrEvent(eventId = eventId),
                cachingProxyEnabled = true,
            )
        }

    @Test
    fun publishEvent_ifCachingProxyEnabled_doesNotThrowIfWeHaveSuccessInBroadcastResult() =
        runTest {
            val eventId = "eventId"
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true) {
                coEvery { broadcastEvents(any(), any()) } returns Result.success(
                    listOf(
                        BroadcastEventResponse(
                            eventId = eventId,
                            responses = listOf(
                                listOf("wss://relay.example.com", """["OK","$eventId",true,""]"""),
                            ),
                        ),
                    ),
                )
            }
            val relayPool = buildRelayPool(cachingImportRepository = cachingImportRepository).apply {
                socketClients = listOf(
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )
            }

            relayPool.publishEvent(
                nostrEvent = buildNostrEvent(eventId = eventId),
                cachingProxyEnabled = true,
            )
        }

    private fun buildQuerySocket(
        url: String,
        incoming: MutableSharedFlow<NostrIncomingMessage>,
    ): NostrSocketClient {
        return mockk(relaxed = true) {
            every { socketUrl } returns url
            every { incomingMessages } returns incoming
            every { connectionGeneration } returns MutableStateFlow(0L)
        }
    }

    @Test
    fun query_deduplicatesEventsByIdAndSendsClose() =
        runTest {
            val relayPool = buildRelayPool()
            relayPool.subscriptionIdFactory = { "sub-1" }
            val event = buildNostrEvent("same-id")
            val incomingA = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 16)
            val incomingB = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 16)
            val clientA = buildQuerySocket("wss://a", incomingA)
            val clientB = buildQuerySocket("wss://b", incomingB)
            relayPool.socketClients = listOf(clientA, clientB)

            val deferred = async { relayPool.query(buildRelayFilter(kinds = listOf(3))) }
            runCurrent()
            incomingA.emit(NostrIncomingMessage.EventMessage(subscriptionId = "sub-1", nostrEvent = event))
            incomingB.emit(NostrIncomingMessage.EventMessage(subscriptionId = "sub-1", nostrEvent = event))
            incomingA.emit(NostrIncomingMessage.EoseMessage(subscriptionId = "sub-1"))
            incomingB.emit(NostrIncomingMessage.EoseMessage(subscriptionId = "sub-1"))
            runCurrent()

            val result = deferred.await()
            result.events.map { it.id } shouldBe listOf("same-id")
            result.duplicateCount shouldBe 1
            result.eoseRelays shouldBe setOf("wss://a", "wss://b")
            relayPool.activeSubscriptionCount() shouldBe 0
            coVerify { clientA.sendCLOSE("sub-1") }
            coVerify { clientB.sendCLOSE("sub-1") }
        }

    @Test
    fun query_oneRelayFailureDoesNotDropEventsFromOthers() =
        runTest {
            val relayPool = buildRelayPool()
            relayPool.subscriptionIdFactory = { "sub-fail" }
            val event = buildNostrEvent("kept")
            val incomingA = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 16)
            val incomingB = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 16)
            val clientA = buildQuerySocket("wss://a", incomingA)
            val clientB = buildQuerySocket("wss://b", incomingB)
            coEvery { clientB.sendREQ(any(), any()) } throws RuntimeException("boom")
            relayPool.socketClients = listOf(clientA, clientB)

            val deferred = async { relayPool.query(buildRelayFilter(kinds = listOf(3))) }
            runCurrent()
            incomingA.emit(NostrIncomingMessage.EventMessage(subscriptionId = "sub-fail", nostrEvent = event))
            incomingA.emit(NostrIncomingMessage.EoseMessage(subscriptionId = "sub-fail"))
            runCurrent()

            val result = deferred.await()
            result.events.map { it.id } shouldBe listOf("kept")
            result.failedRelays.keys shouldBe setOf("wss://b")
            coVerify { clientA.sendCLOSE("sub-fail") }
            coVerify { clientB.sendCLOSE("sub-fail") }
        }

    @Test
    fun query_timeoutsDeadRelayWithoutBlockingOthers() =
        runTest {
            val relayPool = buildRelayPool()
            relayPool.subscriptionIdFactory = { "sub-timeout" }
            val event = buildNostrEvent("alive")
            val incomingAlive = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 16)
            val incomingDead = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 16)
            val alive = buildQuerySocket("wss://alive", incomingAlive)
            val dead = buildQuerySocket("wss://dead", incomingDead)
            relayPool.socketClients = listOf(alive, dead)

            val deferred = async { relayPool.query(buildRelayFilter(kinds = listOf(1))) }
            runCurrent()
            incomingAlive.emit(NostrIncomingMessage.EventMessage(subscriptionId = "sub-timeout", nostrEvent = event))
            incomingAlive.emit(NostrIncomingMessage.EoseMessage(subscriptionId = "sub-timeout"))
            runCurrent()
            testScheduler.advanceTimeBy(RelayPool.SUBSCRIBE_TIMEOUT.toLong())
            runCurrent()

            val result = deferred.await()
            result.events.map { it.id } shouldBe listOf("alive")
            result.failedRelays["wss://dead"] shouldBe "timeout"
            coVerify { alive.sendCLOSE("sub-timeout") }
            coVerify { dead.sendCLOSE("sub-timeout") }
        }

    @Test
    fun query_emptyPoolReturnsEmptyResult() =
        runTest {
            val relayPool = buildRelayPool()
            val result = relayPool.query(buildRelayFilter(kinds = listOf(0)))
            result.events shouldBe emptyList()
            result.eoseRelays shouldBe emptySet()
        }
}
