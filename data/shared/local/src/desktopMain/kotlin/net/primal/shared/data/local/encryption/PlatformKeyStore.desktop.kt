package net.primal.shared.data.local.encryption

import java.security.SecureRandom

/**
 * At-rest key for the JVM target, which exists only to run this module's unit tests — there is no
 * desktop application, so nothing here is ever persisted or shipped.
 *
 * It used to throw, which meant every table with an [Encryptable] column (direct messages, private
 * thread replies) could not be written from a test at all. That is why the DM and private-reply
 * persistence path had no coverage, and why bugs in it reached devices.
 *
 * The key is random per process and never written to disk. A test that wants to verify content
 * round-trips can do so; a test can never accidentally depend on a fixed key, and no data outlives
 * the JVM that produced it.
 */
object JvmPlatformKeyStore : PlatformKeyStore {

    private const val AES_KEY_SIZE_BYTES = 32

    private val key: ByteArray by lazy {
        ByteArray(AES_KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
    }

    override fun getOrCreateKey(): ByteArray = key
}

actual fun createPlatformKeyStore(): PlatformKeyStore = JvmPlatformKeyStore
