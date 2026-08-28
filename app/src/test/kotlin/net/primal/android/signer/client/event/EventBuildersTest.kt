package net.primal.android.signer.client.event

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class EventBuildersTest {

    private val hexPubkey = "73c6bb92440a9344279f7a36aa3de1710c9198b1e9e8a394cd13e0dd5c994c63"
    private val npub = "npub1w0rthyjyp2f5gful0gm2500pwyxfrx93a85289xdz0sd6hyef33sh2cu4x"

    @Test
    fun buildAppSpecificDataEvent_usesHexPubkeyFromAmber() {
        val event = buildAppSpecificDataEvent(pubkey = hexPubkey)
        event.pubKey shouldBe hexPubkey
    }

    @Test
    fun buildAppSpecificDataEvent_decodesNpubToHex() {
        val event = buildAppSpecificDataEvent(pubkey = npub)
        event.pubKey shouldBe hexPubkey
    }

    @Test
    fun buildAppSpecificDataEvent_doesNotSendEmptyPubkeyForHex() {
        val event = buildAppSpecificDataEvent(pubkey = hexPubkey.uppercase())
        event.pubKey shouldNotBe ""
        event.pubKey shouldBe hexPubkey
    }
}
