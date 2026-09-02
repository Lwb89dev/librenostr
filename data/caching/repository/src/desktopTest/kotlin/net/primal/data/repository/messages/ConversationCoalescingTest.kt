package net.primal.data.repository.messages

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.messages.MessagesApi
import net.primal.data.remote.api.messages.model.ConversationRequestBody
import net.primal.data.remote.api.messages.model.ConversationsResponse
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.messages.processors.MessagesProcessor
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Session start pulls the newest page of conversations and so does the messages tab, so opening
 * messages right after launching the app runs both at the same time. These are kind 4 requests,
 * which disclose to a relay who is asking, so sending one instead of two matters more here than
 * it does for public content.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCoalescingTest {

    @Test
    fun `the people you have talked to get their profiles fetched`() =
        runTest {
            // A relay hands back kind 4 and nothing else, so nothing in the response carries a
            // display name. Without asking separately the conversation list shows raw npubs for
            // everyone the database has not met through some other screen.
            withRepository { repository, api, processor ->
                val sync = async { repository.syncConversations(userId = USER_ID, backfillPages = 0) }
                runCurrent()
                api.answer()
                sync.await()

                assertEquals(
                    listOf(listOf(PARTICIPANT_ID)),
                    api.metadataAuthors,
                    "the conversation participant must be asked about",
                )
                // Asking is only half of it: what comes back has to reach the profile store, or
                // the list still renders an npub.
                val stored = slot<List<NostrEvent>>()
                coVerify {
                    processor.processMessageEventsAndSave(
                        userId = any(),
                        messages = any(),
                        profileMetadata = capture(stored),
                        mediaResources = any(),
                        primalUserNames = any(),
                        primalPremiumInfo = any(),
                        primalLegendProfiles = any(),
                        blossomServerEvents = any(),
                    )
                }
                assertEquals(listOf(PARTICIPANT_ID), stored.captured.map { it.pubKey })
            }
        }

    @Test
    fun `two callers wanting the newest page send one set of requests`() =
        runTest {
            withRepository { repository, api, processor ->
                val sessionStart = async { repository.syncConversations(userId = USER_ID, backfillPages = 0) }
                runCurrent()
                val messagesTab = async { repository.syncConversations(userId = USER_ID, backfillPages = 0) }
                runCurrent()
                api.answer()
                sessionStart.await()
                messagesTab.await()

                assertEquals(1, api.requests.size, "the second caller must attach to the first")
            }
        }

    // ------------------------------------------------------------------------------- harness

    /** Holds every conversation request open until the test says what the relays answered. */
    private class GatedMessagesApi : MessagesApi by mockk(relaxed = true) {
        val requests = mutableListOf<ConversationRequestBody>()
        val metadataAuthors = mutableListOf<List<String>>()
        private val gate = CompletableDeferred<Unit>()
        private var nextTimestamp = 1_000L

        fun answer() = gate.complete(Unit).let { }

        override suspend fun getConversations(body: ConversationRequestBody): ConversationsResponse {
            requests += body
            gate.await()
            return ConversationsResponse(
                conversationsSummary = null,
                messages = (0 until (body.limit ?: 0)).map { message(createdAt = nextTimestamp--) },
                profileMetadata = emptyList(),
                cdnResources = emptyList(),
                primalUserNames = null,
                primalLegendProfiles = null,
                primalPremiumInfo = null,
                blossomServers = emptyList(),
            )
        }

        private fun message(createdAt: Long) =
            NostrEvent(
                id = "message-$createdAt",
                pubKey = PARTICIPANT_ID,
                createdAt = createdAt,
                kind = NostrEventKind.EncryptedDirectMessages.value,
                tags = listOf(
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(USER_ID))
                    },
                ),
                content = "ciphertext",
                sig = "sig",
            )
    }

    /** Records which authors were asked about, and answers nothing. */
    private class RecordingQuerier(private val api: GatedMessagesApi) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            if (filter.kinds?.contains(NostrEventKind.Metadata.value) != true) return emptyList()
            api.metadataAuthors += filter.authors.orEmpty()
            return filter.authors.orEmpty().map { metadataEvent(it) }
        }
    }

    private suspend fun TestScope.withRepository(
        block: suspend (ChatRepositoryImpl, GatedMessagesApi, MessagesProcessor) -> Unit,
    ) {
        val databaseName = "primal_conversation_coalescing_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        val api = GatedMessagesApi()
        // Storing a message body goes through encryption at rest and the desktop keystore is not
        // implemented; what is under test is which requests go out and what reaches the store.
        val processor = mockk<MessagesProcessor>(relaxed = true)
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
                every { main() } returns dispatcher
            }
            val repository = ChatRepositoryImpl(
                dispatcherProvider = dispatcherProvider,
                database = database,
                messageCipher = mockk<MessageCipher>(relaxed = true),
                messagesApi = api,
                messagesProcessor = processor,
                primalPublisher = mockk<PrimalPublisher>(relaxed = true),
                relayEventQuerier = RecordingQuerier(api),
                fetchCoordinator = FetchCoordinator(dispatcherProvider = dispatcherProvider),
            )
            block(repository, api, processor)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
        const val PARTICIPANT_ID = "participant-pubkey"
        var counter = 0
    }
}

private fun metadataEvent(pubkey: String) =
    NostrEvent(
        id = "meta-$pubkey",
        pubKey = pubkey,
        createdAt = 1_700_000_000L,
        kind = NostrEventKind.Metadata.value,
        tags = emptyList(),
        content = """{"name":"someone"}""",
        sig = "sig",
    )
