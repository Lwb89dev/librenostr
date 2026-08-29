package net.primal.android.security

import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream

class AESEncryption(
    private val keyAlias: String,
) : Encryption {

    private val encryptionManager = EncryptionManager(
        algorithm = KeyProperties.KEY_ALGORITHM_AES,
        blockMode = KeyProperties.BLOCK_MODE_CBC,
        padding = KeyProperties.ENCRYPTION_PADDING_PKCS7,
    )

    override fun encrypt(raw: String, outputStream: OutputStream) {
        val encryptCipher = encryptionManager.getEncryptCipher(keyAlias = keyAlias)
        val encryptedBytes = encryptCipher.doFinal(raw.toByteArray())
        outputStream.use {
            it.write(encryptCipher.iv.size)
            it.write(encryptCipher.iv)
            it.write(encryptedBytes)
        }
    }

    override fun decrypt(inputStream: InputStream): String {
        val bytes = inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return ""
        val ivSize = bytes[0].toInt() and 0xFF
        if (ivSize != AES_IV_SIZE || bytes.size <= AES_IV_SIZE + 1) {
            return String(bytes, Charsets.UTF_8)
        }
        return try {
            val iv = bytes.copyOfRange(1, AES_IV_SIZE + 1)
            val encrypted = bytes.copyOfRange(AES_IV_SIZE + 1, bytes.size)
            val cipher = encryptionManager.getDecryptCipherForIv(keyAlias, iv)
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Throwable) {
            String(bytes, Charsets.UTF_8)
        }
    }

    companion object {
        private const val AES_IV_SIZE = 16
    }
}
