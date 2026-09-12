package net.primal.shared.data.local.serialization

import androidx.room3.ColumnTypeConverter
import kotlin.io.encoding.ExperimentalEncodingApi
import net.primal.shared.data.local.encryption.CryptoManager
import net.primal.shared.data.local.encryption.Encryptable
import net.primal.shared.data.local.encryption.EncryptionType
import net.primal.shared.data.local.encryption.asEncryptable

/**
 * Identical to [EncryptableTypeConverters] except for the one property that matters here: there is
 * no `enableEncryption` flag to turn off.
 *
 * [EncryptableTypeConverters.enableEncryption] is a single mutable flag shared by every database
 * that registers that object as its type converter — Wallet, Account, *and* the main caching
 * database that stores direct-message and private-reply content. `PrimalApp.onCreate()` calls
 * `WalletRepositoryFactory.init(enableDbEncryption = !BuildConfig.DEBUG)` and
 * `AccountRepositoryFactory.init(enableDbEncryption = !BuildConfig.DEBUG)` purely to make the
 * *wallet* and *account* debug databases easier to inspect during development. Because that flag
 * is one shared global rather than scoped per database, a debug build silently carried that same
 * "write plaintext" setting into the caching database too — even though nothing in the caching
 * database ever asked for it, and its own `Encryptable<T>` columns exist specifically so DM and
 * private-reply content is never on disk unencrypted. A debug build gets side-loaded onto real
 * devices for everyday testing far more often than not, so this was a real, if unintentional,
 * plaintext-DM exposure on exactly the build type developers actually use with their own accounts
 * — release builds were never affected, since `!BuildConfig.DEBUG` is `true` there.
 *
 * [CachingDatabase][net.primal.data.local.db.CachingDatabase] registers this object instead of
 * [EncryptableTypeConverters], so nothing outside its own module can ever flip its encryption off,
 * structurally, regardless of what any other database's debug convenience does. This changes only
 * which Kotlin object serializes the column at read/write time — the underlying SQL column type is
 * unchanged (still `TEXT`), so no schema migration is needed for this switch.
 */
@OptIn(ExperimentalEncodingApi::class)
object AlwaysEncryptedTypeConverters {

    private val encryptionType: EncryptionType = EncryptionType.AES

    @ColumnTypeConverter
    fun fromLong(value: Encryptable<Long>?): String? =
        value?.let { CryptoManager.encrypt(value.decrypted, encryptionType) }

    @ColumnTypeConverter
    fun toLong(value: String?): Encryptable<Long>? =
        value?.let { CryptoManager.decrypt<Long>(value, encryptionType)?.asEncryptable() }

    @ColumnTypeConverter
    fun fromString(value: Encryptable<String>?): String? =
        value?.let { CryptoManager.encrypt(value.decrypted, encryptionType) }

    @ColumnTypeConverter
    fun toString(value: String?): Encryptable<String>? =
        value?.let { CryptoManager.decrypt<String>(value, encryptionType)?.asEncryptable() }

    @ColumnTypeConverter
    fun fromStringList(value: Encryptable<List<String>>?): String? =
        value?.let { CryptoManager.encrypt(value.decrypted, encryptionType) }

    @ColumnTypeConverter
    fun toStringList(value: String?): Encryptable<List<String>>? =
        value?.let { CryptoManager.decrypt<List<String>>(value, encryptionType)?.asEncryptable() }

    @ColumnTypeConverter
    fun fromDouble(value: Encryptable<Double>?): String? =
        value?.let { CryptoManager.encrypt(value.decrypted, encryptionType) }

    @ColumnTypeConverter
    fun toDouble(value: String?): Encryptable<Double>? =
        value?.let { CryptoManager.decrypt<Double>(value, encryptionType)?.asEncryptable() }

    @ColumnTypeConverter
    fun fromInt(value: Encryptable<Int>?): String? =
        value?.let { CryptoManager.encrypt(value.decrypted, encryptionType) }

    @ColumnTypeConverter
    fun toInt(value: String?): Encryptable<Int>? =
        value?.let { CryptoManager.decrypt<Int>(value, encryptionType)?.asEncryptable() }

    @ColumnTypeConverter
    fun fromBoolean(value: Encryptable<Boolean>?): String? =
        value?.let { CryptoManager.encrypt(value.decrypted, encryptionType) }

    @ColumnTypeConverter
    fun toBoolean(value: String?): Encryptable<Boolean>? =
        value?.let { CryptoManager.decrypt<Boolean>(value, encryptionType)?.asEncryptable() }
}
