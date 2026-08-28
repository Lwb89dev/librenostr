package net.primal.domain.nostr.utils

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ValidationUtilsTest {

    @Test
    fun `isValidNostrKey return true for valid nsec`() {
        val nsec = "nsec1j4d3l08h0s5a9uhmjmc68ejxfgz06lpw9sfj7gwvlgkyt3q0j22se7aevw"
        nsec.isValidNostrPrivateKey() shouldBe true
    }

    @Test
    fun `isValidNostrKey return false for invalid nsec`() {
        val nsec = "nsec2j4d3l08h0s5a9uhmjmc68ejxfgz06lpw9sfj7gwvlgkyt3q0j22se7aevw"
        nsec.isValidNostrPrivateKey() shouldBe false
    }

    @Test
    fun `isValidNostrKey return false for empty value`() {
        val actual = "".isValidNostrPrivateKey()
        actual shouldBe false
    }

    @Test
    fun `isValidNostrKey return false for null value`() {
        val actual = null.isValidNostrPrivateKey()
        actual shouldBe false
    }

    @Test
    fun `isValidNostrKey returns true for valid 32 bytes hex value of nsec`() {
        val hex = "955b1fbcf77c29d2f2fb96f1a3e6464a04fd7c2e2c132f21ccfa2c45c40f9295"
        hex.isValidNostrPrivateKey() shouldBe true
    }

    @Test
    fun `isValidNostrKey returns false for less than 32 bytes hex value`() {
        val hex = "955b1fbcf77c29d2f2fb96f1a3e6464a04fd7c2e2c132f21ccfa2c45c40f92"
        hex.isValidNostrPrivateKey() shouldBe false
    }

    @Test
    fun `isValidNostrKey returns false for more than 32 bytes hex value`() {
        val hex = "955b1fbcf77c29d2f2fb96f1a3e6464a04fd7c2e2c132f21ccfa2c45c40f929599"
        hex.isValidNostrPrivateKey() shouldBe false
    }

    @Test
    fun asHexPubkeyOrNull_returnsLowercaseHexForHexPubkey() {
        val hex = "73C6BB92440A9344279F7A36AA3DE1710C9198B1E9E8A394CD13E0DD5C994C63"
        hex.asHexPubkeyOrNull() shouldBe hex.lowercase()
    }

    @Test
    fun asHexPubkeyOrNull_decodesNpubToHex() {
        val npub = "npub1w0rthyjyp2f5gful0gm2500pwyxfrx93a85289xdz0sd6hyef33sh2cu4x"
        npub.asHexPubkeyOrNull() shouldBe "73c6bb92440a9344279f7a36aa3de1710c9198b1e9e8a394cd13e0dd5c994c63"
    }

    @Test
    fun asHexPubkeyOrNull_returnsNullForEmptyPubkey() {
        "".asHexPubkeyOrNull() shouldBe null
    }
}
