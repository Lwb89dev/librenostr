package net.primal.android.signer.bunker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class BunkerUrlTest {

    private val pubkey = "a".repeat(64)

    @Test
    fun `a well-formed bunker url parses pubkey, relays and secret`() {
        val parsed = "bunker://$pubkey?relay=wss://relay.example.com&secret=s3cr3t".parseBunkerUrlOrThrow()

        parsed.pubkey shouldBe pubkey
        parsed.relays shouldBe listOf("wss://relay.example.com")
        parsed.secret shouldBe "s3cr3t"
    }

    @Test
    fun `multiple relay params are all collected, in order`() {
        val url = "bunker://$pubkey?relay=wss://one.example.com&relay=wss://two.example.com"
        url.parseBunkerUrlOrThrow().relays shouldBe listOf("wss://one.example.com", "wss://two.example.com")
    }

    @Test
    fun `a secret is optional`() {
        "bunker://$pubkey?relay=wss://relay.example.com".parseBunkerUrlOrThrow().secret shouldBe null
    }

    @Test
    fun `a relay missing the ws scheme gets wss added, matching every other relay url in this app`() {
        "bunker://$pubkey?relay=relay.example.com".parseBunkerUrlOrThrow().relays shouldBe
            listOf("wss://relay.example.com")
    }

    @Test
    fun `a trailing slash on a relay url is dropped`() {
        "bunker://$pubkey?relay=wss://relay.example.com/".parseBunkerUrlOrThrow().relays shouldBe
            listOf("wss://relay.example.com")
    }

    @Test
    fun `isBunkerUrl is true only for the bunker scheme`() {
        "bunker://$pubkey".isBunkerUrl() shouldBe true
        "nostrconnect://$pubkey".isBunkerUrl() shouldBe false
        "https://example.com".isBunkerUrl() shouldBe false
    }

    @Test
    fun `a url with no bunker prefix is rejected`() {
        shouldThrow<IllegalArgumentException> {
            "nostrconnect://$pubkey?relay=wss://relay.example.com".parseBunkerUrlOrThrow()
        }
    }

    @Test
    fun `a url with no relay is rejected — a bunker channel needs somewhere to reach it`() {
        shouldThrow<IllegalArgumentException> {
            "bunker://$pubkey".parseBunkerUrlOrThrow()
        }
    }

    @Test
    fun `a pubkey that isn't 64 hex characters is rejected`() {
        shouldThrow<IllegalArgumentException> {
            "bunker://not-a-pubkey?relay=wss://relay.example.com".parseBunkerUrlOrThrow()
        }
    }
}
