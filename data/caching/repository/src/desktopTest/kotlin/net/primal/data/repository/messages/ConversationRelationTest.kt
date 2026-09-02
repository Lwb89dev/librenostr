package net.primal.data.repository.messages

import androidx.room3.useWriterConnection
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.messages.MessageConversationData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.messages.MessagesApi
import net.primal.data.remote.api.messages.model.ConversationRequestBody
import net.primal.data.remote.api.messages.model.ConversationsResponse
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.messages.processors.MessagesProcessor
import net.primal.domain.messages.ConversationRelation
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The messages screen has two tabs, and they were showing the same thing. Every row was written as
 * Follows and the query ignored the column anyway, so "Others" was a copy of the list beside it.
 *
 * A NIP-04 event says nothing about whether you wanted the conversation, so the split has to be
 * decided locally. The rule is the one Amethyst settled on: a conversation is yours if you follow
 * the other person or if you have written to them, and everything else is a request. Answering
 * somebody is what accepts them, which is what stops the second tab being a dead end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRelationTest {

    @Test
    fun `a stranger who wrote first is a request`() =
        runTest {
            withRepository { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(
                    ConversationRelation.Other,
                    database.relationOf(STRANGER),
                    "somebody you neither follow nor answered is a request",
                )
            }
        }

    @Test
    fun `somebody you follow is accepted without you writing back`() =
        runTest {
            withRepository(follows = listOf(FRIEND)) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(ConversationRelation.Follows, database.relationOf(FRIEND))
            }
        }

    @Test
    fun `answering a stranger accepts them`() =
        runTest {
            withRepository { repository, database ->
                // The reply is what changes the answer, so it has to be there before the sync.
                database.insertSentMessage(to = STRANGER)

                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(
                    ConversationRelation.Follows,
                    database.relationOf(STRANGER),
                    "writing to somebody is how you accept them",
                )
            }
        }

    @Test
    fun `a reply discovered in the same page as the stranger's message is accepted`() =
        runTest {
            // The failure this pins: on a fresh sync with nothing in the database yet, both sides
            // of a conversation can arrive in the same page. The reply is not persisted until
            // after classification runs, so a check against the database alone would call this a
            // stranger and there would be no later page to correct it — the conversation shown in
            // the list already carries its newest message, and nothing revisits an old one.
            val bothDirectionsInOnePage = listOf(
                incomingMessage(from = STRANGER),
                replyMessage(to = STRANGER),
            )
            withRepository(api = FakeMessagesApi(messages = bothDirectionsInOnePage)) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(
                    ConversationRelation.Follows,
                    database.relationOf(STRANGER),
                    "the reply in this same page is what accepts them",
                )
            }
        }

    @Test
    fun `a row written before the split is reclassified`() =
        runTest {
            withRepository { repository, database ->
                // Every conversation stored by an earlier version claims to be accepted. This one
                // already holds the newest message it will ever see, so the row upsert has nothing
                // to rewrite and the reclassification is the only thing that can move it.
                database.insertConversation(participantId = STRANGER, relation = ConversationRelation.Follows)

                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(ConversationRelation.Other, database.relationOf(STRANGER))
            }
        }

    @Test
    fun `an old conversation misfiled before this fix exists is corrected without a relay round trip`() =
        runTest {
            // The exact shape of the bug found on device: a conversation months old, already
            // carrying a reply the database has held the whole time, wrongly filed as a request
            // because it was classified before the fix that reads this page's own messages
            // existed — and it will never surface in a fresh fetch again to get a second chance,
            // because the sync only ever asks for the newest events. Fixing it cannot depend on
            // the relay handing the old event back.
            withRepository(api = FakeMessagesApi(messages = emptyList())) { repository, database ->
                database.insertConversation(participantId = STRANGER, relation = ConversationRelation.Other)
                database.insertSentMessage(to = STRANGER)

                repository.syncConversations(userId = ME, backfillPages = 0)

                assertEquals(ConversationRelation.Follows, database.relationOf(STRANGER))
            }
        }

    @Test
    fun `the two tabs no longer hold the same conversations`() =
        runTest {
            withRepository(follows = listOf(FRIEND)) { repository, database ->
                repository.syncConversations(userId = ME, backfillPages = 0)

                val accepted = database.participantsIn(ConversationRelation.Follows)
                val requests = database.participantsIn(ConversationRelation.Other)

                assertEquals(listOf(FRIEND), accepted)
                assertEquals(listOf(STRANGER), requests)
            }
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun CachingDatabase.relationOf(participantId: String) =
        messageConversations().findAllByOwnerId(ownerId = ME)
            .first { it.participantId == participantId }
            .relation

    private suspend fun CachingDatabase.participantsIn(relation: ConversationRelation) =
        messageConversations().findAllByOwnerId(ownerId = ME)
            .filter { it.relation == relation }
            .map { it.participantId }

    private suspend fun CachingDatabase.insertConversation(participantId: String, relation: ConversationRelation) {
        val conversation = MessageConversationData(
            ownerId = ME,
            participantId = participantId,
            lastMessageId = "known-message",
            // Newer than anything the refresh brings back, so the row upsert skips it and only a
            // deliberate reclassification can move it.
            lastMessageAt = 5_000L,
            unreadMessagesCount = 0,
            relation = relation,
            participantMetadataId = null,
        )
        messageConversations().upsertAll(data = listOf(conversation))
    }

    /**
     * Writes a sent message straight to SQLite.
     *
     * A message body is encrypted at rest through a type converter, and the desktop target has no
     * key store, so the normal insert cannot run here. Only the plain columns matter for who was
     * written to, so the row goes in with the encrypted ones left as placeholders.
     */
    private suspend fun CachingDatabase.insertSentMessage(to: String) =
        useWriterConnection { transactor ->
            transactor.usePrepared(
                "INSERT INTO DirectMessageData " +
                    "(messageId, ownerId, senderId, receiverId, participantId, createdAt, " +
                    "content, uris, hashtags) " +
                    "VALUES ('sent-1', '$ME', '$ME', '$to', '$to', 5, 'x', 'x', 'x')",
            ) { it.step() }
        }

    private class FakeMessagesApi(
        private val messages: List<NostrEvent> = listOf(
            incomingMessage(from = STRANGER),
            incomingMessage(from = FRIEND),
        ),
    ) : MessagesApi by mockk(relaxed = true) {
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

    private class FollowListQuerier(private val follows: List<String>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            if (filter.kinds?.contains(NostrEventKind.FollowList.value) != true) return emptyList()
            return listOf(
                NostrEvent(
                    id = "follow-list",
                    pubKey = ME,
                    createdAt = 1_700_000_000L,
                    kind = NostrEventKind.FollowList.value,
                    tags = follows.map { pubkey ->
                        buildJsonArray {
                            add(JsonPrimitive("p"))
                            add(JsonPrimitive(pubkey))
                        }
                    },
                    content = "",
                    sig = "sig",
                ),
            )
        }
    }

    private suspend fun TestScope.withRepository(
        follows: List<String> = emptyList(),
        api: FakeMessagesApi = FakeMessagesApi(),
        block: suspend (ChatRepositoryImpl, CachingDatabase) -> Unit,
    ) {
        val databaseName = "primal_conversation_relation_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
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
                messagesProcessor = mockk<MessagesProcessor>(relaxed = true),
                primalPublisher = mockk<PrimalPublisher>(relaxed = true),
                relayEventQuerier = FollowListQuerier(follows),
                fetchCoordinator = FetchCoordinator(dispatcherProvider = dispatcherProvider),
            )
            block(repository, database)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private companion object {
        const val ME = "me-pubkey"
        const val FRIEND = "friend-pubkey"
        const val STRANGER = "stranger-pubkey"
        var counter = 0
    }
}

private fun incomingMessage(from: String) =
    NostrEvent(
        id = "message-from-$from",
        pubKey = from,
        createdAt = 1_000L,
        kind = NostrEventKind.EncryptedDirectMessages.value,
        tags = listOf(
            buildJsonArray {
                add(JsonPrimitive("p"))
                add(JsonPrimitive("me-pubkey"))
            },
        ),
        content = "ciphertext",
        sig = "sig",
    )

private fun replyMessage(to: String) =
    NostrEvent(
        id = "reply-to-$to",
        pubKey = "me-pubkey",
        createdAt = 2_000L,
        kind = NostrEventKind.EncryptedDirectMessages.value,
        tags = listOf(
            buildJsonArray {
                add(JsonPrimitive("p"))
                add(JsonPrimitive(to))
            },
        ),
        content = "ciphertext",
        sig = "sig",
    )
