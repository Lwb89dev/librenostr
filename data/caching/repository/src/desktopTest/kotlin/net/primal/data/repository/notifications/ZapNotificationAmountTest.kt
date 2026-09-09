package net.primal.data.repository.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.notifications.NotificationGroup

class ZapNotificationAmountTest {

    @Test
    fun `reads zap amount from the receipt bolt11 invoice`() = runBlocking {
        val result = fetch(zapReceipt(bolt11 = BOLT11_12_345_SATS, requestAmountMillisats = "999000"))

        assertEquals(12_345L, result.notifications.single().satsZapped)
    }

    @Test
    fun `falls back to the request millisat amount when invoice is missing`() = runBlocking {
        val result = fetch(zapReceipt(bolt11 = null, requestAmountMillisats = "21000"))

        assertEquals(21L, result.notifications.single().satsZapped)
    }

    private suspend fun fetch(receipt: NostrEvent): RelayNotificationsResult =
        RelayNotificationsFetcher(querier = FixedQuerier(receipt))
            .fetch(userId = RECEIVER_ID, group = NotificationGroup.ZAPS, limit = 20)

    private class FixedQuerier(private val receipt: NostrEvent) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> =
            if (filter.kinds.orEmpty().contains(NostrEventKind.Zap.value)) listOf(receipt) else emptyList()
    }

    private fun zapReceipt(bolt11: String?, requestAmountMillisats: String): NostrEvent {
        val request = NostrEvent(
            id = "zap-request",
            pubKey = SENDER_ID,
            createdAt = 1_000L,
            kind = 9734,
            tags = listOf(
                tag("p", RECEIVER_ID),
                tag("e", NOTE_ID),
                tag("amount", requestAmountMillisats),
            ),
            content = "",
            sig = "request-signature",
        )
        return NostrEvent(
            id = "zap-receipt",
            pubKey = "lnurl-provider",
            createdAt = 2_000L,
            kind = NostrEventKind.Zap.value,
            tags = buildList {
                add(tag("p", RECEIVER_ID))
                add(tag("e", NOTE_ID))
                bolt11?.let { add(tag("bolt11", it)) }
                add(tag("description", request.encodeToJsonString()))
            },
            content = "",
            sig = "receipt-signature",
        )
    }

    private fun tag(vararg values: String): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private companion object {
        const val RECEIVER_ID = "receiver-pubkey"
        const val SENDER_ID = "sender-pubkey"
        const val NOTE_ID = "zapped-note"

        const val BOLT11_12_345_SATS =
            "lnbc123450n1pj7welppp53umfyxp6jn9uvkt463hydtq2zpfvz78hxhpfv9wqx6v4uwdw2rnqdzqg3hkuct5v56zu3n4dcsxgmmwv96x" +
                "jmmwyp6x7gzqgpc8ymmrv4h8ghmrwfuhqar0cqzpgxqrrsssp5tntqjpngx6l8y9va9tzd7fmtemtyp5vvsqphw8f8yqjjrr26x5qs9" +
                "qyyssqyyv7tqp5kpsmv6s5825kcq8fxsn4ag2h5uj2j6lnsnclyyq6844khayzqrl7yue46nwlukfr4uftqcwzxzh8krqg9rqsg9tg6x" +
                "ggszcp0gyjcd"
    }
}
