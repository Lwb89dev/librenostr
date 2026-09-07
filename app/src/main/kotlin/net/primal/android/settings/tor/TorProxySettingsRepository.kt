package net.primal.android.settings.tor

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import net.primal.core.networking.tor.TorProxySettings
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.utils.coroutines.DispatcherProvider

@Singleton
class TorProxySettingsRepository @Inject constructor(
    dispatchers: DispatcherProvider,
    @ApplicationContext context: Context,
) {
    private val persistence = TorProxySettingsStore.dataStore(context)
    private val scope = CoroutineScope(dispatchers.io())

    val settings: StateFlow<TorProxySettings> = persistence.data.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = runBlocking { persistence.data.first() },
    )

    suspend fun setEnabled(enabled: Boolean) {
        persistence.updateData { it.copy(enabled = enabled) }
    }

    suspend fun setPort(port: Int) {
        persistence.updateData { it.copy(socksPort = port) }
    }
}
