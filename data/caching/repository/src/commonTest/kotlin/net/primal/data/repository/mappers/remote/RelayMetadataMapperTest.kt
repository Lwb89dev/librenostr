package net.primal.data.repository.mappers.remote

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import net.primal.domain.nostr.NostrEvent

class RelayMetadataMapperTest {

    private fun metadataEvent(
        id: String,
        pubkey: String,
        createdAt: Long,
        name: String,
        displayName: String? = null,
        picture: String? = null,
        nip05: String? = null,
    ): NostrEvent {
        val display = displayName?.let { ",\"display_name\":\"$it\"" } ?: ""
        val pic = picture?.let { ",\"picture\":\"$it\"" } ?: ""
        val nip = nip05?.let { ",\"nip05\":\"$it\"" } ?: ""
        return NostrEvent(
            id = id,
            pubKey = pubkey,
            createdAt = createdAt,
            kind = 0,
            content = """{"name":"$name"$display$pic$nip}""",
            sig = "sig",
        )
    }

    @Test
    fun latestMetadataByPubkey_keepsNewestReplaceableEvent() {
        val older = metadataEvent(id = "old", pubkey = "alice", createdAt = 1, name = "old")
        val newer = metadataEvent(id = "new", pubkey = "alice", createdAt = 9, name = "new")
        val bob = metadataEvent(id = "bob", pubkey = "bob", createdAt = 5, name = "bob")

        val actual = listOf(older, bob, newer).latestMetadataByPubkey().associateBy { it.pubKey }

        actual["alice"]?.id shouldBe "new"
        actual["bob"]?.id shouldBe "bob"
        actual.size shouldBe 2
    }

    @Test
    fun asProfileDataPOFromRelay_readsKind0FieldsWithoutPrimalEnrichment() {
        val event = metadataEvent(
            id = "evt",
            pubkey = "alice",
            createdAt = 42,
            name = "alice",
            displayName = "Alice",
            picture = "https://example.com/a.png",
            nip05 = "alice@example.com",
        )

        val profile = event.asProfileDataPOFromRelay()

        profile.ownerId shouldBe "alice"
        profile.handle shouldBe "alice"
        profile.displayName shouldBe "Alice"
        profile.internetIdentifier shouldBe "alice@example.com"
        profile.avatarCdnImage?.sourceUrl shouldBe "https://example.com/a.png"
        profile.avatarCdnImage?.variants shouldBe emptyList()
        profile.primalPremiumInfo?.primalName shouldBe null
    }
}
