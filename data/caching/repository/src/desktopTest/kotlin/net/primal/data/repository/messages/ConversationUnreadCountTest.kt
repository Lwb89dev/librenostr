package net.primal.data.repository.messages

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import net.primal.domain.messages.Nip17Message
import net.primal.domain.messages.Nip17Transport
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Cover for the number behind the Messages badge.
 *
 * Every conversation-index builder used to write a hardcoded `unreadMessagesCount = 0`, so the
 * badge counted nothing no matter how many messages arrived — which read from outside as "the
 * recipient never gets notified". The count is derived at persist time instead, and it has to
 * survive the thing that happens most: the same page being fetched again at every session start.
 */
class ConversationUnreadCountTest {

    @Test
    fun `an incoming message counts as unread`() =
        runTest {
            withRepository(messages = listOf(incoming(from = SENDER, at = 1_000))) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(1, database.unreadCountOf(SENDER))
            }
        }

    @Test
    fun `a message this account sent is never unread`() =
        runTest {
            withRepository(messages = listOf(outgoing(to = SENDER, at = 1_000))) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(0, database.unreadCountOf(SENDER))
            }
        }

    /** Every session start re-fetches the same page; it must not keep re-counting it. */
    @Test
    fun `re-syncing the same page does not inflate the count`() =
        runTest {
            withRepository(messages = listOf(incoming(from = SENDER, at = 1_000))) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)
                repository.syncConversations(userId = ME, backfillPages = 0)
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(1, database.unreadCountOf(SENDER))
            }
        }

    @Test
    fun `reading a conversation clears it and a later sync leaves it read`() =
        runTest {
            withRepository(messages = listOf(incoming(from = SENDER, at = 1_000))) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)
                repository.markConversationAsReadLocally(userId = ME, conversationUserId = SENDER)

                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(0, database.unreadCountOf(SENDER), "a read conversation must stay read")
            }
        }

    @Test
    fun `several incoming messages each count once`() =
        runTest {
            val page = listOf(
                incoming(from = SENDER, at = 1_000, id = "a"),
                incoming(from = SENDER, at = 2_000, id = "b"),
                incoming(from = SENDER, at = 3_000, id = "c"),
            )
            withRepository(messages = page) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(3, database.unreadCountOf(SENDER))
            }
        }

    /** The path a modern DM actually takes: unsealed by the transport, never a kind-4 event. */
    @Test
    fun `an incoming NIP-17 message counts as unread and a private reply does not`() =
        runTest {
            val transport = StaticNip17Transport(
                messages = listOf(
                    nip17Dm(from = SENDER, at = 1_000),
                    // A private reply is not a conversation, and must not put the Messages tab
                    // in the unread state for something that belongs in the notifications feed.
                    nip17PrivateReply(from = SENDER, at = 2_000),
                ),
            )
            withRepository(messages = emptyList(), transport = transport) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(1, database.unreadCountOf(SENDER))
            }
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun CachingDatabase.unreadCountOf(participantId: String) =
        messageConversations().findAllByOwnerId(ownerId = ME)
            .first { it.participantId == participantId }
            .unreadMessagesCount

    private class StaticMessagesApi(private val messages: List<NostrEvent>) :
        MessagesApi by mockk(relaxed = true) {
        override suspend fun getConversations(body: ConversationRequestBody) =
            ConversationsResponse(
                conversationsSummary = null,
                messages = messages,
                profileMetadata = emptyList(),
                cdnResources = emptyList(),
                primalUserNames = null,
                primalLegendProfiles = null,
                primalPremiumInfo = null,
                blossomServers = emptyList(),
            )
    }

    private object EmptyQuerier : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> = emptyList()
    }

    private class StaticNip17Transport(private val messages: List<Nip17Message>) :
        Nip17Transport by mockk(relaxed = true) {
        override suspend fun fetchMessages(userId: String, limit: Int) = messages

        override fun subscribeMessages(userId: String): Flow<Nip17Message> = emptyFlow()
    }

    private fun nip17Dm(from: String, at: Long) =
        Nip17Message(
            eventId = "nip17-dm-$at",
            outerEventId = "wrap-$at",
            kind = NostrEventKind.PrivateDirectMessage.value,
            senderId = from,
            recipientIds = listOf(ME),
            createdAt = at,
            content = "hello",
            tags = emptyList(),
        )

    private fun nip17PrivateReply(from: String, at: Long) =
        Nip17Message(
            eventId = "nip17-reply-$at",
            outerEventId = "wrap-$at",
            kind = NostrEventKind.ShortTextNote.value,
            senderId = from,
            recipientIds = listOf(ME),
            createdAt = at,
            content = "private reply",
            tags = listOf(
                buildJsonArray {
                    add(JsonPrimitive("e"))
                    add(JsonPrimitive("1".repeat(64)))
                    add(JsonPrimitive(""))
                    add(JsonPrimitive("root"))
                },
            ),
        )

    private suspend fun TestScope.withRepository(
        messages: List<NostrEvent>,
        transport: Nip17Transport? = null,
        block: suspend (ChatRepositoryImpl, CachingDatabase) -> Unit,
    ) {
        val databaseName = "primal_conversation_unread_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
                every { main() } returns dispatcher
            }
            block(
                ChatRepositoryImpl(
                    dispatcherProvider = dispatcherProvider,
                    database = database,
                    messageCipher = mockk<MessageCipher>(relaxed = true),
                    messagesApi = StaticMessagesApi(messages),
                    messagesProcessor = mockk<MessagesProcessor>(relaxed = true),
                    primalPublisher = mockk<PrimalPublisher>(relaxed = true),
                    relayEventQuerier = EmptyQuerier,
                    fetchCoordinator = FetchCoordinator(dispatcherProvider = dispatcherProvider),
                    nip17Transport = transport,
                ),
                database,
            )
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private fun incoming(from: String, at: Long, id: String = "in-$at") =
        message(id = id, author = from, recipient = ME, at = at)

    private fun outgoing(to: String, at: Long, id: String = "out-$at") =
        message(id = id, author = ME, recipient = to, at = at)

    private fun message(id: String, author: String, recipient: String, at: Long) =
        NostrEvent(
            id = id,
            pubKey = author,
            createdAt = at,
            kind = NostrEventKind.EncryptedDirectMessages.value,
            tags = listOf(
                buildJsonArray {
                    add(JsonPrimitive("p"))
                    add(JsonPrimitive(recipient))
                },
            ),
            content = "ciphertext",
            sig = "sig",
        )

    private companion object {
        const val ME = "me-pubkey"
        const val SENDER = "sender-pubkey"
        var counter = 0
    }
}
