package net.primal.android.security

import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class AESEncryptionTest {

    @Test
    fun decrypt_rejectsUnframedPlaintextJson() {
        val encryption = AESEncryption(keyAlias = "unused")
        val plaintext = """[{"npub":"npub1abc","nsec":null}]"""
        assertThrows(IllegalStateException::class.java) {
            encryption.decrypt(plaintext.byteInputStream())
        }
    }

    @Test
    fun decrypt_emptyStream_returnsEmptyString() {
        val encryption = AESEncryption(keyAlias = "unused")
        encryption.decrypt(ByteArrayInputStream(ByteArray(0))) shouldBe ""
    }
}
