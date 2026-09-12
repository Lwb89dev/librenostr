package net.primal.android.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

class EncryptionManager(
    private val algorithm: String,
    private val blockMode: String,
    private val padding: String,
    private val context: Context? = null,
) {

    companion object {
        private const val KEY_STORE_PROVIDER = "AndroidKeyStore"
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    private val transformation = "$algorithm/$blockMode/$padding"

    private val keyStore by lazy {
        KeyStore.getInstance(KEY_STORE_PROVIDER).apply {
            load(null)
        }
    }

    fun getEncryptCipher(keyAlias: String): Cipher {
        return Cipher.getInstance(transformation).apply {
            init(Cipher.ENCRYPT_MODE, resolveSecretKey(keyAlias))
        }
    }

    fun getDecryptCipherForIv(keyAlias: String, iv: ByteArray): Cipher {
        return Cipher.getInstance(transformation).apply {
            val parameterSpec = if (blockMode == KeyProperties.BLOCK_MODE_GCM) {
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            } else {
                IvParameterSpec(iv)
            }
            init(Cipher.DECRYPT_MODE, resolveSecretKey(keyAlias), parameterSpec)
        }
    }

    private fun resolveSecretKey(keyAlias: String): SecretKey {
        val existingKey = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createSecretKey(keyAlias)
    }

    private fun createSecretKey(keyAlias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(algorithm, KEY_STORE_PROVIDER)
        val builder = createKeyGenParameterSpecBuilder(keyAlias)

        // Mirrors AndroidPlatformKeyStore's StrongBox handling: not every StrongBox-advertising
        // device can actually back every algorithm/mode combination, so a request can still throw
        // at generation time. Falling back to the normal (TEE-backed) key on failure is required,
        // not optional.
        if (hasStrongBox()) {
            try {
                builder.setIsStrongBoxBacked(true)
                keyGenerator.init(builder.build())
                return keyGenerator.generateKey()
            } catch (_: Exception) {
                builder.setIsStrongBoxBacked(false)
            }
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) == true

    private fun createKeyGenParameterSpecBuilder(keyAlias: String): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(blockMode)
            .setEncryptionPaddings(padding)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
}
