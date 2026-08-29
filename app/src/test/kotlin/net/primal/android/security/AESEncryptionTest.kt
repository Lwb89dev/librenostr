package net.primal.android.security

import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.Test

class AESEncryptionTest {

    @Test
    fun decrypt_readsLegacyPlaintextJson() {
        val encryption = AESEncryption(keyAlias = "unused")
        val plaintext = """[{"npub":"npub1abc","nsec":null}]"""
        encryption.decrypt(plaintext.byteInputStream()) shouldBe plaintext
    }

    @Test
    fun decrypt_emptyStream_returnsEmptyString() {
        val encryption = AESEncryption(keyAlias = "unused")
        encryption.decrypt(ByteArrayInputStream(ByteArray(0))) shouldBe ""
    }
}
