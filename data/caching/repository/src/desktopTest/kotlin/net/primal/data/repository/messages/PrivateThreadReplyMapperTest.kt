package net.primal.data.repository.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.data.repository.mappers.remote.hasPrivateThreadMarkers
import net.primal.data.repository.mappers.remote.mapAsPrivateThreadReplyPO
import net.primal.domain.messages.Nip17Message

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
        assertFalse(message(tags = emptyList()).hasPrivateThreadMarkers())
        assertTrue(message().hasPrivateThreadMarkers())
    }

    @Test
    fun `wrong recipient is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            message().mapAsPrivateThreadReplyPO(userId = "d".repeat(64))
        }
    }

    @Test
    fun `missing duplicate and malformed relationship markers are rejected`() {
        assertFailsWith<IllegalStateException> {
            message(tags = listOf(eventTag(ROOT, "root"))).mapAsPrivateThreadReplyPO(RECIPIENT)
        }
        assertFailsWith<IllegalStateException> {
            message(tags = listOf(eventTag(ROOT, "root"), eventTag(ROOT, "root"), eventTag(PARENT, "reply")))
                .mapAsPrivateThreadReplyPO(RECIPIENT)
        }
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

    private fun message(tags: List<kotlinx.serialization.json.JsonArray> = defaultTags()) = Nip17Message(
        eventId = EVENT_ID,
        outerEventId = "outer-wrap",
        senderId = SENDER,
        recipientIds = listOf(RECIPIENT),
        createdAt = 42,
        content = "private text",
        tags = tags,
    )

    private fun defaultTags() = listOf(eventTag(ROOT, "root"), eventTag(PARENT, "reply"))

    private fun eventTag(id: String, marker: String) = buildJsonArray {
        add(JsonPrimitive("e"))
        add(JsonPrimitive(id))
        add(JsonPrimitive(""))
        add(JsonPrimitive(marker))
    }

    private companion object {
        val EVENT_ID = "0".repeat(64)
        val ROOT = "1".repeat(64)
        val PARENT = "2".repeat(64)
        val SENDER = "a".repeat(64)
        val RECIPIENT = "b".repeat(64)
    }
}
