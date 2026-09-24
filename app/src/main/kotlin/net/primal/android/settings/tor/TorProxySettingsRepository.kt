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
import net.primal.core.networking.tor.NetworkMode
import net.primal.core.networking.tor.NetworkRoute
import net.primal.core.networking.tor.TorEngineType
import net.primal.core.networking.tor.TorProxySettings
import net.primal.core.networking.tor.TorProxySettingsStore
import net.primal.core.networking.tor.toRouteConfig
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

    suspend fun setMode(mode: NetworkMode) = update { it.withMode(mode) }

    suspend fun setPort(port: Int) = update { it.copy(socksPort = port) }

    suspend fun setEngine(engine: TorEngineType) = update { it.copy(engine = engine) }

    /**
     * Saves the change and puts it in force before returning. `BuiltInTor` also follows the saved
     * settings, but on its own coroutine; updating the route here as well means that by the time the
     * user sees the switch flip, no new connection can still take the old route.
     */
    private suspend fun update(transform: (TorProxySettings) -> TorProxySettings) {
        val saved = persistence.updateData(transform)
        NetworkRoute.controller.update(saved.toRouteConfig())
    }
}
