package net.primal.data.repository.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationType

/**
 * Accounts that follow and unfollow in a loop republish their whole kind 3 list every cycle, so
 * every cycle carries a fresh event id. Keying notification rows by event id turned one such
 * account into seven identical "followed you" rows inside the same minute.
 */
class FollowSpamCollapseTest {

    @Test
    fun `a follow and unfollow loop becomes one notification`() =
        runBlocking {
            // Seven republished follow lists from one account, as observed on device.
            val events = (0 until 7).map { followList(pubkey = BOT, createdAt = DAY_START + it * 60) }

            val result = fetch(events)

            assertEquals(1, result.notifications.size, "one account following today is one row")
            assertEquals(BOT, result.notifications.first().actionUserId)
        }

    @Test
    fun `different followers on the same day stay separate rows`() =
        runBlocking {
            // Collapsing must be per person, not per day: who followed you is the whole content
            // of the notification.
            val events = listOf(
                followList(pubkey = BOT, createdAt = DAY_START),
                followList(pubkey = "second-follower", createdAt = DAY_START + 30),
            )

            val result = fetch(events)

            assertEquals(2, result.notifications.size)
        }

    @Test
    fun `the same follower on a later day is a new row`() =
        runBlocking {
            // A genuine follow months later is a different fact, not a duplicate.
            val events = listOf(
                followList(pubkey = BOT, createdAt = DAY_START),
                followList(pubkey = BOT, createdAt = DAY_START + SECONDS_PER_DAY),
            )

            val result = fetch(events)

            assertEquals(2, result.notifications.size)
        }

    @Test
    fun `reactions are still kept apart by event id`() =
        runBlocking {
            // Only follows are republished; collapsing content events would lose real activity.
            val events = (0 until 3).map { reaction(id = "reaction-$it", createdAt = DAY_START + it) }

            val result = fetch(events)

            assertEquals(3, result.notifications.size)
            assertEquals(NotificationType.YOUR_POST_WAS_LIKED, result.notifications.first().type)
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun fetch(events: List<NostrEvent>): RelayNotificationsResult =
        RelayNotificationsFetcher(querier = FixedQuerier(events))
            .fetch(userId = USER_ID, group = NotificationGroup.ALL, limit = 100)

    private class FixedQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            return if (kinds.contains(NostrEventKind.FollowList.value) ||
                kinds.contains(NostrEventKind.Reaction.value)
            ) {
                events
            } else {
                emptyList()
            }
        }
    }

    private fun followList(pubkey: String, createdAt: Long) =
        NostrEvent(
            // A republished list is a different event every time; that is the whole problem.
            id = "follow-event-$pubkey-$createdAt",
            pubKey = pubkey,
            createdAt = createdAt,
            kind = NostrEventKind.FollowList.value,
            tags = listOf(pubkeyTag(USER_ID)),
            content = "",
            sig = "sig",
        )

    private fun reaction(id: String, createdAt: Long) =
        NostrEvent(
            id = id,
            pubKey = BOT,
            createdAt = createdAt,
            kind = NostrEventKind.Reaction.value,
            tags = listOf(eventTag("target-note"), pubkeyTag(USER_ID)),
            content = "+",
            sig = "sig",
        )

    private fun pubkeyTag(value: String) =
        buildJsonArray {
            add(JsonPrimitive("p"))
            add(JsonPrimitive(value))
        }

    private fun eventTag(value: String) =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(value))
        }

    private companion object {
        const val USER_ID = "me-pubkey"
        const val BOT = "bot-pubkey"
        const val SECONDS_PER_DAY = 86_400L

        /** An arbitrary midnight, so the day-boundary cases are unambiguous. */
        const val DAY_START = 1_756_684_800L
    }
}
