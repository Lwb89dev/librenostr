package net.primal.android.security

import androidx.datastore.core.CorruptionException
import io.github.aakira.napier.Napier
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Suppress("TooGenericExceptionCaught", "ThrowsCount")
inline fun <reified T> InputStream.readDecrypted(json: Json, encryption: Encryption): T {
    val decryptedJson = try {
        encryption.decrypt(this)
    } catch (error: Exception) {
        Napier.w(throwable = error) { "Unable to decrypt stored value." }
        throw CorruptionException("Unable to decrypt stored value.", error)
    }
    return try {
        json.decodeFromString(decryptedJson)
    } catch (error: SerializationException) {
        Napier.w(throwable = error) { "Unable to deserialize decrypted value." }
        throw CorruptionException("Unable to deserialize decrypted value.", error)
    } catch (error: IllegalArgumentException) {
        Napier.w(throwable = error) { "Unable to deserialize decrypted value." }
        throw CorruptionException("Unable to deserialize decrypted value.", error)
    }
}

inline fun <reified T> OutputStream.writeEncrypted(
    value: T,
    json: Json,
    encryption: Encryption,
) {
    encryption.encrypt(json.encodeToString(value), this)
}
