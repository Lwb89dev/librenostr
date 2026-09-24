package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import org.junit.Test

class OnionHostTest {

    @Test
    fun `an onion service name is recognised`() {
        "abcdefghijklmnop.onion".isOnionHost() shouldBe true
        "sub.abcdefghijklmnop.onion".isOnionHost() shouldBe true
    }

    @Test
    fun `case and a trailing dot do not hide an onion name`() {
        // Each of these spellings names the same host, so each must be caught, or it is a bypass.
        "ABCDEFGHIJKLMNOP.ONION".isOnionHost() shouldBe true
        "abcdefghijklmnop.onion.".isOnionHost() shouldBe true
        " abcdefghijklmnop.onion ".isOnionHost() shouldBe true
    }

    @Test
    fun `a clearnet host that merely contains the word is not an onion service`() {
        "abcdefghijklmnop.onion.example.com".isOnionHost() shouldBe false
        "onion.example.com".isOnionHost() shouldBe false
        "notonion".isOnionHost() shouldBe false
        "abc-onion".isOnionHost() shouldBe false
        "abcdefghijklmnop.onions".isOnionHost() shouldBe false
    }

    @Test
    fun `the bare word and empty names are not onion services`() {
        "onion".isOnionHost() shouldBe false
        ".onion".isOnionHost() shouldBe true
        "".isOnionHost() shouldBe false
    }
}
