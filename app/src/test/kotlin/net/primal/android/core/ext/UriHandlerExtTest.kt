package net.primal.android.core.ext

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UriHandlerExtTest {

    @Test
    fun https_isAllowed() {
        isSafeExternalUri("https://example.com/a") shouldBe true
    }

    @Test
    fun javascript_isBlocked() {
        isSafeExternalUri("javascript:alert(1)") shouldBe false
    }

    @Test
    fun intent_isBlocked() {
        isSafeExternalUri("intent://evil#Intent;end") shouldBe false
    }

    @Test
    fun file_isBlocked() {
        isSafeExternalUri("file:///data/data/secrets") shouldBe false
    }

    @Test
    fun nostrconnect_isBlocked() {
        isSafeExternalUri("nostrconnect://attacker") shouldBe false
    }

    @Test
    fun walletConnect_isBlocked() {
        isSafeExternalUri("nostr+walletconnect://secret") shouldBe false
    }

    @Test
    fun lightning_isAllowed() {
        isSafeExternalUri("lightning:lnbc1whatever") shouldBe true
    }
}
