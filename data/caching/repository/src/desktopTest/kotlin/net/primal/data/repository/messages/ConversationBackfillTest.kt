package net.primal.data.repository.messages

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The session-start DM backfill walks backwards with `until`. Left unpinned, two failures are
 * silent: the walk asks for the same window forever because `until` never moved past the oldest
 * event, or it gives up after one page because a short page was not read as the end of history.
 *
 * The third test pins why the walk is safe to run at all: an older page must not drag a
 * conversation's last-message timestamp backwards and reorder the inbox.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationBackfillTest {

    @Test
    fun `walks back through pages and never asks for the same window twice`() =
        withRepository { repository, api, _ ->
            repository.syncConversations(userId = USER_ID, backfillPages = 3)

            val untils = api.conversationRequests.map { it.until }
            // 100 events per page, descending from 1000: the windows are 1000..901, 900..801 and
            // so on, so each request starts strictly before the previous page's oldest event.
            assertEquals(listOf(null, 900L, 800L, 700L), untils, "each page must start before the last")
            assertTrue(untils.distinct().size == untils.size, "a repeated window means the walk is stuck")
        }

    @Test
    fun `stops early when a page comes back short`() =
        withRepository(eventsPerPage = 5) { repository, api, _ ->
            repository.syncConversations(userId = USER_ID, backfillPages = 3)

            assertEquals(1, api.conversationRequests.size, "a short page is the end of the walk")
        }

    @Test
    fun `an older page does not move a conversation backwards in time`() =
        withRepository { repository, _, database ->
            repository.syncConversations(userId = USER_ID, backfillPages = 3)

            val conversations = database.messageConversations().findAllByOwnerId(ownerId = USER_ID)
            assertEquals(1, conversations.size)
            // The newest event of the whole walk is the first one of page one.
            assertEquals(1_000L, conversations.first().lastMessageAt, "the newest message must win")
        }

    // ------------------------------------------------------------------------------- harness

    /** Returns [eventsPerPage] messages per call, each older than the last, recording the request. */
    private class RecordingMessagesApi(private val eventsPerPage: Int) : MessagesApi by mockk(relaxed = true) {
        val conversationRequests = mutableListOf<ConversationRequestBody>()
        private var nextTimestamp = 1_000L

        override suspend fun getConversations(body: ConversationRequestBody): ConversationsResponse {
            conversationRequests += body
            return ConversationsResponse(
                conversationsSummary = null,
                messages = (0 until eventsPerPage).map { message(createdAt = nextTimestamp--) },
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

    /** Decryption is not what these tests are about; hand the content back untouched. */
    private class PassThroughCipher : MessageCipher {
        override fun encryptMessage(
            userId: String,
            participantId: String,
            content: String,
        ) = content

        override fun decryptMessage(
            userId: String,
            participantId: String,
            content: String,
        ) = content
    }

    private fun withRepository(
        eventsPerPage: Int = 100,
        block: suspend (ChatRepositoryImpl, RecordingMessagesApi, CachingDatabase) -> Unit,
    ) = runBlocking {
        val databaseName = "primal_conversation_backfill_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        val api = RecordingMessagesApi(eventsPerPage)
        try {
            val dispatcher = UnconfinedTestDispatcher()
            val repository = ChatRepositoryImpl(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
                database = database,
                messageCipher = PassThroughCipher(),
                messagesApi = api,
                // Storing a message body goes through encryption at rest, and the desktop
                // keystore is not implemented. What is under test here is the walk and the
                // conversation index, both of which use the real database.
                messagesProcessor = mockk<MessagesProcessor>(relaxed = true),
                primalPublisher = mockk<PrimalPublisher>(relaxed = true),
                fetchCoordinator = FetchCoordinator(
                    dispatcherProvider = mockk<DispatcherProvider> {
                        every { io() } returns dispatcher
                        every { main() } returns dispatcher
                    },
                ),
            )
            block(repository, api, database)
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
