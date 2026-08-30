package net.primal.core.config

import io.github.aakira.napier.Napier
import net.primal.core.config.store.AppConfigDataStore
import net.primal.core.utils.Result
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.core.utils.updater.Updater

class AppConfigHandler internal constructor(
    @Suppress("UNUSED_PARAMETER")
    dispatcherProvider: DispatcherProvider,
    private val appConfigStore: AppConfigDataStore,
    @Suppress("UNUSED_PARAMETER")
    private val wellKnownApi: Any? = null,
) : Updater() {

    override suspend fun doUpdate(): Result<Unit> {
        Napier.d { "Relay-only mode: centralized endpoint discovery is disabled." }
        return Result.success(Unit)
    }

    suspend fun overrideCacheUrl(url: String) = appConfigStore.overrideCacheUrl(url = url)

    suspend fun restoreDefaultCacheUrl() {
        appConfigStore.revertCacheUrlOverrideFlag()
        appConfigStore.updateConfig {
            copy(cacheUrl = DEFAULT_APP_CONFIG.cacheUrl)
        }
    }
}
