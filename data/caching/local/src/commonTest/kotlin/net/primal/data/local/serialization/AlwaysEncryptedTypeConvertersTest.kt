package net.primal.data.local.serialization

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.AfterTest
import kotlin.test.Test
import net.primal.shared.data.local.encryption.asEncryptable
import net.primal.shared.data.local.serialization.AlwaysEncryptedTypeConverters
import net.primal.shared.data.local.serialization.EncryptableTypeConverters

class AlwaysEncryptedTypeConvertersTest {

    @AfterTest
    fun resetSharedEncryptionFlag() {
        EncryptableTypeConverters.enableEncryption = true
    }

    @Test
    fun `fromString then toString round-trips the original value`() {
        val original = "a direct message that must never touch disk as plaintext"

        val stored = AlwaysEncryptedTypeConverters.fromString(original.asEncryptable())
        val restored = AlwaysEncryptedTypeConverters.toString(stored)

        restored?.decrypted shouldBe original
    }

    @Test
    fun `stays encrypted even when the shared EncryptableTypeConverters flag is turned off`() {
        // This is exactly what WalletRepositoryFactory/AccountRepositoryFactory do on a debug
        // build (enableDbEncryption = !BuildConfig.DEBUG): they flip the flag that
        // EncryptableTypeConverters shares with every database that registers it. Before this
        // fix, CachingDatabase registered that same shared object, so this line alone was enough
        // to silently write plaintext DMs and private-thread replies to disk on debug builds.
        EncryptableTypeConverters.enableEncryption = false

        val original = "a direct message that must never touch disk as plaintext"

        val storedByAlwaysEncrypted = AlwaysEncryptedTypeConverters.fromString(original.asEncryptable())
        val storedByTheSharedFlagObject = EncryptableTypeConverters.fromString(original.asEncryptable())

        // Proof the flag really is off: the shared object now writes recoverable plaintext.
        storedByTheSharedFlagObject shouldBe "\"$original\""

        // Proof the fix holds: AlwaysEncryptedTypeConverters ignores that flag entirely, so the
        // caching database's column never becomes plaintext no matter what Wallet/Account do.
        storedByAlwaysEncrypted shouldNotBe storedByTheSharedFlagObject
        storedByAlwaysEncrypted shouldNotBe original
        AlwaysEncryptedTypeConverters.toString(storedByAlwaysEncrypted)?.decrypted shouldBe original
    }
}
