package net.primal.android.security

import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class AESEncryption(
    private val keyAlias: String,
) : Encryption {

    private val encryptionManager = EncryptionManager(
        algorithm = KeyProperties.KEY_ALGORITHM_AES,
        blockMode = KeyProperties.BLOCK_MODE_GCM,
        padding = KeyProperties.ENCRYPTION_PADDING_NONE,
    )

    /**
     * The old implementation used unauthenticated CBC and silently fell back to plaintext.
     * Keep a read-only migration path for existing files, but write only authenticated GCM.
     */
    private val legacyEncryptionManager = EncryptionManager(
        algorithm = KeyProperties.KEY_ALGORITHM_AES,
        blockMode = KeyProperties.BLOCK_MODE_CBC,
        padding = KeyProperties.ENCRYPTION_PADDING_PKCS7,
    )

    private val gcmKeyAlias = "$keyAlias.gcm.v2"
    private val legacyKeyAlias = keyAlias

    override fun encrypt(raw: String, outputStream: OutputStream) {
        val encryptCipher = encryptionManager.getEncryptCipher(keyAlias = gcmKeyAlias)
        val encryptedBytes = encryptCipher.doFinal(raw.toByteArray())
        outputStream.use {
            // Versioned framing: [version][iv size][iv][ciphertext + GCM tag].
            it.write(FORMAT_VERSION)
            it.write(encryptCipher.iv.size)
            it.write(encryptCipher.iv)
            it.write(encryptedBytes)
        }
    }

    override fun decrypt(inputStream: InputStream): String {
        val bytes = inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return ""
        return when {
            bytes[0].toInt() == FORMAT_VERSION -> decryptGcm(bytes)
            else -> decryptLegacyCbc(bytes)
        }
    }

    private fun decryptGcm(bytes: ByteArray): String {
        val ivSize = bytes.getOrNull(VERSION_IV_SIZE_INDEX)?.toInt()?.and(BYTE_MASK) ?: throw invalidCiphertext()
        if (ivSize != GCM_IV_SIZE || bytes.size < MIN_GCM_PAYLOAD_SIZE) throw invalidCiphertext()
        val payloadStart = HEADER_SIZE + ivSize
        if (bytes.size <= payloadStart) throw invalidCiphertext()
        val iv = bytes.copyOfRange(HEADER_SIZE, payloadStart)
        val encrypted = bytes.copyOfRange(payloadStart, bytes.size)
        return decryptBytes(
            cipher = encryptionManager.getDecryptCipherForIv(gcmKeyAlias, iv),
            encrypted = encrypted,
        )
    }

    private fun decryptLegacyCbc(bytes: ByteArray): String {
        val ivSize = bytes[0].toInt() and BYTE_MASK
        if (ivSize != LEGACY_CBC_IV_SIZE || bytes.size <= ivSize + 1) throw invalidCiphertext()
        val iv = bytes.copyOfRange(1, ivSize + 1)
        val encrypted = bytes.copyOfRange(ivSize + 1, bytes.size)
        return decryptBytes(
            cipher = legacyEncryptionManager.getDecryptCipherForIv(legacyKeyAlias, iv),
            encrypted = encrypted,
        )
    }

    private fun decryptBytes(cipher: Cipher, encrypted: ByteArray): String = try {
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (error: GeneralSecurityException) {
        throw IllegalStateException("Encrypted local data failed authentication.", error)
    }

    private fun invalidCiphertext() = IllegalStateException("Invalid encrypted local data.")

    companion object {
        private const val FORMAT_VERSION = 2
        private const val HEADER_SIZE = 2
        private const val VERSION_IV_SIZE_INDEX = 1
        private const val BYTE_MASK = 0xFF
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 16
        private const val MIN_GCM_PAYLOAD_SIZE = HEADER_SIZE + GCM_IV_SIZE + GCM_TAG_SIZE
        private const val LEGACY_CBC_IV_SIZE = 16
    }
}
