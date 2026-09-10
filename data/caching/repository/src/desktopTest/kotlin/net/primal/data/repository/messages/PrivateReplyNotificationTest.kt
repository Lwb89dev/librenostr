package net.primal.data.repository.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.mappers.local.asNotificationDO
import net.primal.data.repository.messages.processors.MessagesProcessor
import net.primal.domain.messages.Nip17Message
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.MessageCipher
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * End-to-end cover for the one thing that tells a private reply's recipient it exists.
 *
 * A gift-wrapped reply is invisible to every relay, so no relay-sourced notification can ever
 * mention it. Unless unsealing one writes a local notification row, the reply lands silently in a
 * table only the opened thread reads, and the recipient has no way to learn it arrived at all.
 */
class PrivateReplyNotificationTest {

    @Test
    fun `an incoming private reply becomes an unseen notification carrying the decrypted note`() =
        withProcessor { database, processor ->
            processor.processNip17MessagesAndSave(userId = RECIPIENT, messages = listOf(privateReply()))

            val unseen = database.notifications()
                .unseenByGroup(ownerId = RECIPIENT, groupKey = NotificationGroup.ALL.name, showFollows = true)
                .first()

            assertEquals(1, unseen.size, "the recipient must get exactly one notification")
            val notification = unseen.single().asNotificationDO()
            assertEquals(NotificationType.YOUR_POST_WAS_PRIVATELY_REPLIED_TO, notification.type)
            assertEquals(SENDER, notification.actionUserId)

            val post = assertNotNull(notification.actionOnPost, "the note body must render, not a bare header")
            assertTrue(post.isPrivate, "the note must be marked private so the UI draws the lock")
            assertEquals("private text", post.content)
            assertEquals(PARENT, post.threadRelation?.parentId, "tapping it must open the thread it answers")
        }

    /** The Replies tab has to show it too, not only the combined feed. */
    @Test
    fun `the notification is filed under both the all and replies groups`() =
        withProcessor { database, processor ->
            processor.processNip17MessagesAndSave(userId = RECIPIENT, messages = listOf(privateReply()))

            val replies = database.notifications()
                .unseenByGroup(ownerId = RECIPIENT, groupKey = NotificationGroup.REPLIES.name, showFollows = true)
                .first()

            assertEquals(1, replies.size)
        }

    /** A self-copy of an outgoing reply is not news, and must not notify its own author. */
    @Test
    fun `the sender's own copy produces no notification`() =
        withProcessor { database, processor ->
            processor.processNip17MessagesAndSave(userId = SENDER, messages = listOf(privateReply()))

            val unseen = database.notifications()
                .unseenByGroup(ownerId = SENDER, groupKey = NotificationGroup.ALL.name, showFollows = true)
                .first()

            assertTrue(unseen.isEmpty(), "an author must not be notified of their own reply")
        }

    /**
     * Every session start re-fetches the same gift wraps. Re-processing one must not resurrect a
     * notification the user has already read.
     */
    @Test
    fun `reprocessing the same reply does not mark it unread again`() =
        withProcessor { database, processor ->
            processor.processNip17MessagesAndSave(userId = RECIPIENT, messages = listOf(privateReply()))
            database.notifications().markAllUnseenNotificationsAsSeen(ownerId = RECIPIENT, seenAt = 1_700_000_100)

            processor.processNip17MessagesAndSave(userId = RECIPIENT, messages = listOf(privateReply()))

            val unseen = database.notifications()
                .unseenByGroup(ownerId = RECIPIENT, groupKey = NotificationGroup.ALL.name, showFollows = true)
                .first()

            assertTrue(unseen.isEmpty(), "an already-read reply must stay read")
        }

    /** An ordinary NIP-17 chat message belongs in the inbox, not in the notifications feed. */
    @Test
    fun `a direct message produces no notification row`() =
        withProcessor { database, processor ->
            processor.processNip17MessagesAndSave(userId = RECIPIENT, messages = listOf(directMessage()))

            val unseen = database.notifications()
                .unseenByGroup(ownerId = RECIPIENT, groupKey = NotificationGroup.ALL.name, showFollows = true)
                .first()

            assertTrue(unseen.isEmpty())
        }

    private fun privateReply() = Nip17Message(
        eventId = REPLY_ID,
        outerEventId = "outer-wrap",
        kind = NostrEventKind.ShortTextNote.value,
        senderId = SENDER,
        recipientIds = listOf(RECIPIENT),
        createdAt = 1_700_000_000,
        content = "private text",
        tags = listOf(eventTag(ROOT, "root"), eventTag(PARENT, "reply")),
    )

    private fun directMessage() = Nip17Message(
        eventId = DM_ID,
        outerEventId = "outer-wrap-dm",
        kind = NostrEventKind.PrivateDirectMessage.value,
        senderId = SENDER,
        recipientIds = listOf(RECIPIENT),
        createdAt = 1_700_000_000,
        content = "hello",
        tags = emptyList(),
    )

    private fun eventTag(id: String, marker: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(id))
            add(JsonPrimitive(""))
            add(JsonPrimitive(marker))
        }

    private fun withProcessor(block: suspend (CachingDatabase, MessagesProcessor) -> Unit) =
        runBlocking {
            val databaseName = "primal_private_reply_notification_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                block(
                    database,
                    MessagesProcessor(
                        database = database,
                        messageCipher = NoopCipher,
                        fetchCoordinator = FetchCoordinator(dispatcherProvider = TestDispatchers),
                    ),
                )
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private object NoopCipher : MessageCipher {
        override fun encryptMessage(userId: String, participantId: String, content: String) = content

        override fun decryptMessage(userId: String, participantId: String, content: String) = content
    }

    private object TestDispatchers : DispatcherProvider {
        override fun io() = Dispatchers.Default

        override fun main() = Dispatchers.Default
    }

    private companion object {
        var counter = 0
        val REPLY_ID = "0".repeat(64)
        val DM_ID = "9".repeat(64)
        val ROOT = "1".repeat(64)
        val PARENT = "2".repeat(64)
        val SENDER = "a".repeat(64)
        val RECIPIENT = "b".repeat(64)
    }
}
