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
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.broadcast.BroadcastApi
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.links.EventUriType
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
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

    /**
     * Regression cover for the reason a note you just published showed a raw `nostr:npub1…`
     * instead of the mentioned person's name, and only fixed itself once some unrelated relay
     * fetch reprocessed the same note later — this is the immediate, no-network-round-trip path
     * that should have rendered it right away, since the mentioned person's profile is already
     * local (mentioning them at all requires having found them in the mention search first).
     */
    @Test
    fun cacheNostrEvents_resolvesAMentionOfAnAlreadyKnownProfileImmediately() =
        withRepository { repository, database ->
            database.profiles().insertOrUpdateAll(
                data = listOf(profileData(ownerId = MENTIONED_USER_ID, handle = "alice")),
            )
            val mentionUri = "nostr:${MENTIONED_USER_ID.hexToNpubHrp()}"
            val note = noteEvent(
                id = "mentions-1",
                pubkey = USER_ID,
                content = "hey $mentionUri check this out",
                tags = listOf(
                    buildJsonArray {
                        add("p")
                        add(MENTIONED_USER_ID)
                        add("")
                        add("mention")
                    },
                ),
            )

            repository.cacheNostrEvents(events = listOf(note))

            val stored = database.eventUris().findEventNostrUrisByEventId("mentions-1").single()
            stored.type shouldBe EventUriNostrType.Profile
            stored.referencedUser.shouldNotBeNull()
            stored.referencedUser?.userId shouldBe MENTIONED_USER_ID
        }

    /**
     * Same regression, for a quoted note instead of a mentioned person: quoting requires having
     * the target note loaded already, so its content is local and needs no relay round trip to
     * render as a card right away.
     */
    @Test
    fun cacheNostrEvents_resolvesAQuoteOfAnAlreadyKnownNoteImmediately() =
        withRepository { repository, database ->
            // A quote card needs the quoted note's author too ("Alice: ..."), so their profile
            // has to be known for the same reason a relay-fetched quote needs it — this is not
            // special-cased for the just-published path, only made to run without a relay trip.
            database.profiles().insertOrUpdateAll(data = listOf(profileData(ownerId = USER_ID, handle = "me")))
            repository.cacheNostrEvents(
                events = listOf(noteEvent(id = QUOTED_NOTE_ID, pubkey = USER_ID, content = "the original")),
            )
            val quoteUri = "nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}"
            val quoting = noteEvent(id = "quotes-1", pubkey = USER_ID, content = "look at this $quoteUri")

            repository.cacheNostrEvents(events = listOf(quoting))

            val stored = database.eventUris().findEventNostrUrisByEventId("quotes-1").single()
            stored.type shouldBe EventUriNostrType.Note
            stored.referencedNote.shouldNotBeNull()
            stored.referencedNote?.postId shouldBe QUOTED_NOTE_ID
        }

    /**
     * A mention of nobody this device knows cannot show a real name yet, but — matching how the
     * existing classifier already treats a relay-fetched mention of an unknown profile, not a
     * behavior introduced here — it still classifies as a profile mention with a synthesized
     * ellipsized-npub display, rather than falling back to "unsupported" or a null user.
     */
    @Test
    fun cacheNostrEvents_fallsBackToAnEllipsizedNpubForAnUnknownMention() =
        withRepository { repository, database ->
            val unknownId = "f".repeat(64)
            val note = noteEvent(
                id = "mentions-unknown",
                pubkey = USER_ID,
                content = "hey nostr:${unknownId.hexToNpubHrp()}",
            )

            repository.cacheNostrEvents(events = listOf(note))

            val stored = database.eventUris().findEventNostrUrisByEventId("mentions-unknown").single()
            stored.type shouldBe EventUriNostrType.Profile
            stored.referencedUser.shouldNotBeNull()
            stored.referencedUser?.userId shouldBe unknownId
        }

    /** An attached image classifies by URL alone — it needs no CDN round trip to render at all. */
    @Test
    fun cacheNostrEvents_classifiesAnAttachedImageImmediately() =
        withRepository { repository, database ->
            val note = noteEvent(
                id = "with-image",
                pubkey = USER_ID,
                content = "check this out\n\nhttps://blossom.example.com/abc123.jpg",
            )

            repository.cacheNostrEvents(events = listOf(note))

            val attachments = database.eventUris().loadEventUris(noteId = "with-image")
            attachments.map { it.type } shouldContain EventUriType.Image
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

    private fun profileData(ownerId: String, handle: String) =
        ProfileData(
            ownerId = ownerId,
            eventId = "metadata-$ownerId",
            createdAt = 1_700_000_000L,
            handle = handle,
            raw = "{}",
        )

    companion object {
        private const val USER_ID = "user-pubkey-hex-for-local-persist-tests-32bytes!!"
        private const val FOLLOWING_NOTES_SPEC = "{\"id\":\"latest\",\"kind\":\"notes\"}"
        private const val FOLLOWING_NOTES_LWR_SPEC =
            "{\"id\":\"latest\",\"include_replies\":true,\"kind\":\"notes\"}"

        // Real 64-hex ids, not the placeholder USER_ID above — these get bech32-encoded
        // (hexToNpubHrp/hexToNoteHrp) to build the nostr: mention/quote URIs under test.
        private val MENTIONED_USER_ID = "a".repeat(64)
        private val QUOTED_NOTE_ID = "b".repeat(64)

        private var counter = 0
    }
}
