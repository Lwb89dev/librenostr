package net.primal.data.repository.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.notifications.NotificationGroup

/**
 * A notification's note can quote another note, and that quote is fetched so the row can show it.
 * Only the notification's own actors used to get a kind 0 fetched, so the quoted note was stored
 * with nobody's name to head it and the quote card stayed "Mentioned event not found".
 */
class NotificationQuotedAuthorsTest {

    @Test
    fun `the authors of quoted notes get their profile fetched`() =
        runBlocking {
            val querier = RecordingQuerier()

            RelayNotificationsFetcher(querier = querier)
                .fetch(userId = USER_ID, group = NotificationGroup.ALL, limit = 50)

            val profileRequests = querier.metadataRequests
            assertTrue(
                profileRequests.any { QUOTED_AUTHOR_ID in it },
                "the quoted note's author must be asked for, asked for: $profileRequests",
            )
        }

    @Test
    fun `an author who is already a notification actor is not asked for twice`() =
        runBlocking {
            val querier = RecordingQuerier(quotedAuthor = ACTOR_ID)

            RelayNotificationsFetcher(querier = querier)
                .fetch(userId = USER_ID, group = NotificationGroup.ALL, limit = 50)

            assertEquals(
                1,
                querier.metadataRequests.count { ACTOR_ID in it },
                "the actor's profile is already part of the page",
            )
        }

    // ------------------------------------------------------------------------------- harness

    private class RecordingQuerier(private val quotedAuthor: String = QUOTED_AUTHOR_ID) : RelayEventQuerier {
        /** The author lists of every kind 0 request, in the order they were made. */
        val metadataRequests = mutableListOf<List<String>>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            return when {
                kinds == listOf(NostrEventKind.Metadata.value) -> {
                    metadataRequests += filter.authors.orEmpty()
                    emptyList()
                }

                filter.ids != null -> listOf(quotedNote()).filter { it.id in filter.ids.orEmpty() }
                kinds.contains(NostrEventKind.ShortTextNote.value) -> listOf(mention())
                else -> emptyList()
            }
        }

        private fun mention() =
            NostrEvent(
                id = MENTION_ID,
                pubKey = ACTOR_ID,
                createdAt = 1_700_000_100L,
                kind = NostrEventKind.ShortTextNote.value,
                tags = listOf(
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(USER_ID))
                    },
                ),
                content = "@you look at nostr:${QUOTED_NOTE_ID.hexToNoteHrp()}",
                sig = "sig",
            )

        private fun quotedNote() =
            NostrEvent(
                id = QUOTED_NOTE_ID,
                pubKey = quotedAuthor,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.ShortTextNote.value,
                tags = emptyList(),
                content = "the quoted note",
                sig = "sig",
            )
    }

    private companion object {
        const val USER_ID = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val ACTOR_ID = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val QUOTED_AUTHOR_ID = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
        const val MENTION_ID = "1111111111111111111111111111111111111111111111111111111111111111"
        const val QUOTED_NOTE_ID = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
