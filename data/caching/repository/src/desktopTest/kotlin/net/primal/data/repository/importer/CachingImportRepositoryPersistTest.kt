package net.primal.data.repository.importer

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.broadcast.BroadcastApi
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.domain.nostr.NostrEvent
import net.primal.shared.data.local.db.LocalDatabaseFactory

@OptIn(ExperimentalCoroutinesApi::class)
class CachingImportRepositoryPersistTest {

    @Test
    fun cacheNostrEvents_persistsNoteAndAttachesToFollowingFeed() =
        withRepository { repository, database ->
            val event = noteEvent(id = "published-1", pubkey = USER_ID, content = "from relays")

            repository.cacheNostrEvents(events = listOf(event))

            val stored = database.posts().findByPostId("published-1")
            stored.shouldNotBeNull()
            stored.content shouldBe "from relays"

            val membership = database.feedsConnections().findFirstBySpec(
                ownerId = USER_ID,
                spec = FOLLOWING_NOTES_SPEC,
            )
            membership.shouldNotBeNull()
            membership.eventId shouldBe "published-1"
        }

    @Test
    fun cacheNostrEvents_attachesReplyOnlyToLwrFeed() =
        withRepository { repository, database ->
            val reply = noteEvent(
                id = "reply-1",
                pubkey = USER_ID,
                content = "a reply",
                tags = listOf(
                    buildJsonArray {
                        add("e")
                        add("root-1")
                    },
                ),
            )

            repository.cacheNostrEvents(events = listOf(reply))

            database.posts().findByPostId("reply-1").shouldNotBeNull()

            val latest = database.feedsConnections().findFirstBySpec(USER_ID, FOLLOWING_NOTES_SPEC)
            latest shouldBe null

            val lwr = database.feedsConnections().findFirstBySpec(USER_ID, FOLLOWING_NOTES_LWR_SPEC)
            lwr.shouldNotBeNull()
            lwr.eventId shouldBe "reply-1"
        }

    @Test
    fun cacheNostrEvents_linksReplyToParentThread() =
        withRepository { repository, database ->
            val reply = noteEvent(
                id = "reply-2",
                pubkey = USER_ID,
                content = "thread reply",
                tags = listOf(
                    buildJsonArray {
                        add("e")
                        add("parent-1")
                        add("")
                        add("reply")
                    },
                ),
            )

            repository.cacheNostrEvents(events = listOf(reply))

            val links = database.threadConversations().findConversationRefs(noteId = "parent-1")
            links.map { it.replyNoteId } shouldContain "reply-2"
        }

    private fun withRepository(
        block: suspend (CachingImportRepositoryImpl, CachingDatabase) -> Unit,
    ) = runBlocking {
        val databaseName = "primal_local_persist_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        try {
            val testDispatcher = UnconfinedTestDispatcher()
            val dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns testDispatcher
                every { main() } returns testDispatcher
            }
            val repository = CachingImportRepositoryImpl(
                dispatcherProvider = dispatcherProvider,
                database = database,
                importApi = mockk(relaxed = true),
                broadcastApi = mockk<BroadcastApi>(relaxed = true),
                invalidationTracker = FeedSpecInvalidationTracker(),
            )
            block(repository, database)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private fun noteEvent(
        id: String,
        pubkey: String,
        content: String,
        tags: List<kotlinx.serialization.json.JsonArray> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = tags,
        content = content,
        sig = "sig",
    )

    companion object {
        private const val USER_ID = "user-pubkey-hex-for-local-persist-tests-32bytes!!"
        private const val FOLLOWING_NOTES_SPEC = "{\"id\":\"latest\",\"kind\":\"notes\"}"
        private const val FOLLOWING_NOTES_LWR_SPEC =
            "{\"id\":\"latest\",\"include_replies\":true,\"kind\":\"notes\"}"
        private var counter = 0
    }
}
