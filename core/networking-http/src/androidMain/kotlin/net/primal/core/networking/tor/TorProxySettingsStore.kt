package net.primal.core.networking.tor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

private const val TOR_PROXY_SETTINGS_FILE_NAME = "tor_proxy_settings.json"

/**
 * The single memoized [DataStore] instance for Tor proxy settings, shared by every consumer:
 * the Ktor engine chokepoint (via [TorProxyContextHolder]), the standalone raw-OkHttp sites
 * (image loader, media downloader, crash reporter/language packs), and the Hilt-injected
 * settings-screen repository. AndroidX DataStore throws if two independent instances are ever
 * created against the same file, so every access path funnels through here rather than each
 * calling `DataStoreFactory.create` on its own.
 */
object TorProxySettingsStore {

    @Volatile
    private var instance: DataStore<TorProxySettings>? = null

    fun dataStore(context: Context): DataStore<TorProxySettings> =
        instance ?: synchronized(this) {
            instance ?: createDataStore(context).also { instance = it }
        }

    fun readBlocking(context: Context): TorProxySettings = runBlocking { dataStore(context).data.first() }

    private fun createDataStore(context: Context): DataStore<TorProxySettings> {
        val path = context.applicationContext.dataStoreFile(TOR_PROXY_SETTINGS_FILE_NAME).path
        return DataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = TorProxySettingsSerialization,
                producePath = { path.toPath() },
            ),
        )
    }
}
