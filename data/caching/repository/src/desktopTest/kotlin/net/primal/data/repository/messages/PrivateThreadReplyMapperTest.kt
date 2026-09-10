package net.primal.data.repository.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.data.repository.mappers.remote.isPrivateThreadReply
import net.primal.data.repository.mappers.remote.mapAsPrivateThreadReplyPO
import net.primal.domain.messages.Nip17Message
import net.primal.domain.nostr.NostrEventKind

class PrivateThreadReplyMapperTest {

    @Test
    fun `root parent and plaintext enter encrypted local fields`() {
        val stored = message().mapAsPrivateThreadReplyPO(userId = RECIPIENT)

        assertEquals(ROOT, stored.rootId.decrypted)
        assertEquals(PARENT, stored.parentId.decrypted)
        assertEquals("private text", stored.content.decrypted)
        assertEquals("outer-wrap", stored.outerEventId)
    }

    @Test
    fun `an ordinary NIP-17 DM is not classified as a thread reply`() {
        assertFalse(message(kind = NostrEventKind.PrivateDirectMessage.value).isPrivateThreadReply())
        assertFalse(message(tags = emptyList()).isPrivateThreadReply())
        assertTrue(message().isPrivateThreadReply())
    }

    /**
     * NIP-10 lets a reply directly to the thread root carry a single `root`-marked tag and no
     * `reply` marker at all. That is the shape Amethyst produces for the most common private
     * reply there is, and requiring both markers dropped every one of them.
     */
    @Test
    fun `a reply naming only a root resolves its parent to that root`() {
        val stored = message(tags = listOf(eventTag(ROOT, "root")))
            .mapAsPrivateThreadReplyPO(userId = RECIPIENT)

        assertEquals(ROOT, stored.rootId.decrypted)
        assertEquals(ROOT, stored.parentId.decrypted)
    }

    /**
     * A malformed tag set from some other client is not a reason to hide a delivered message:
     * the first tag of each marker wins instead of the whole reply being rejected.
     */
    @Test
    fun `duplicate markers take the first match instead of failing`() {
        val stored = message(
            tags = listOf(eventTag(ROOT, "root"), eventTag(OTHER, "root"), eventTag(PARENT, "reply")),
        ).mapAsPrivateThreadReplyPO(userId = RECIPIENT)

        assertEquals(ROOT, stored.rootId.decrypted)
        assertEquals(PARENT, stored.parentId.decrypted)
    }

    /** The deprecated positional convention: first bare `e` is the root, last is the parent. */
    @Test
    fun `markerless positional tags are read as root then parent`() {
        val stored = message(tags = listOf(bareEventTag(ROOT), bareEventTag(PARENT)))
            .mapAsPrivateThreadReplyPO(userId = RECIPIENT)

        assertEquals(ROOT, stored.rootId.decrypted)
        assertEquals(PARENT, stored.parentId.decrypted)
    }

    @Test
    fun `a mention tag is never mistaken for the thread relationship`() {
        val stored = message(tags = listOf(eventTag(ROOT, "root"), eventTag(OTHER, "mention")))
            .mapAsPrivateThreadReplyPO(userId = RECIPIENT)

        assertEquals(ROOT, stored.rootId.decrypted)
        assertEquals(ROOT, stored.parentId.decrypted)
    }

    @Test
    fun `wrong recipient is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            message().mapAsPrivateThreadReplyPO(userId = "d".repeat(64))
        }
    }

    @Test
    fun `a reply naming no event at all is rejected`() {
        assertFailsWith<IllegalStateException> {
            message(tags = emptyList()).mapAsPrivateThreadReplyPO(RECIPIENT)
        }
    }

    @Test
    fun `a malformed event id is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            message(tags = listOf(eventTag("not-an-id", "root"), eventTag(PARENT, "reply")))
                .mapAsPrivateThreadReplyPO(RECIPIENT)
        }
    }

    @Test
    fun `self-referencing private reply is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            message(tags = listOf(eventTag(ROOT, "root"), eventTag(EVENT_ID, "reply")))
                .mapAsPrivateThreadReplyPO(RECIPIENT)
        }
    }

    private fun message(tags: List<JsonArray> = defaultTags(), kind: Int = NostrEventKind.ShortTextNote.value) =
        Nip17Message(
            eventId = EVENT_ID,
            outerEventId = "outer-wrap",
            kind = kind,
            senderId = SENDER,
            recipientIds = listOf(RECIPIENT),
            createdAt = 42,
            content = "private text",
            tags = tags,
        )

    private fun defaultTags() = listOf(eventTag(ROOT, "root"), eventTag(PARENT, "reply"))

    private fun eventTag(id: String, marker: String) =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(id))
            add(JsonPrimitive(""))
            add(JsonPrimitive(marker))
        }

    private fun bareEventTag(id: String) =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(id))
        }

    private companion object {
        val EVENT_ID = "0".repeat(64)
        val ROOT = "1".repeat(64)
        val PARENT = "2".repeat(64)
        val OTHER = "3".repeat(64)
        val SENDER = "a".repeat(64)
        val RECIPIENT = "b".repeat(64)
    }
}
