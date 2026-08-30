package net.primal.core.config

import androidx.datastore.core.DataStore
import de.jensklingenberg.ktorfit.Ktorfit
import net.primal.core.config.api.WellKnownApi
import net.primal.core.config.api.createWellKnownApi
import net.primal.core.config.store.AppConfigDataStore
import net.primal.core.config.store.createAppConfigDataStorePersistence
import net.primal.core.networking.factory.HttpClientFactory
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.domain.global.AppConfig

// Kept only for binary compatibility with the legacy API-client module. The application
// data paths use RelayPool/Blossom directly and never consume these service endpoints.
private const val CONFIG_RELAY_COMPAT = "wss://relay.damus.io"
private const val CONFIG_BLOSSOM_COMPAT = "https://blossom.band"

internal val DEFAULT_APP_CONFIG = AppConfig(
    cacheUrl = CONFIG_RELAY_COMPAT,
    uploadUrl = CONFIG_BLOSSOM_COMPAT,
    walletUrl = CONFIG_RELAY_COMPAT,
)

object AppConfigFactory {

    private val dispatcherProvider by lazy { createDispatcherProvider() }

    private val httpClient = HttpClientFactory.createHttpClientWithDefaultConfig()

    private val wellKnownApi: WellKnownApi by lazy {
        Ktorfit.Builder()
            .baseUrl("https://nostrich.org/")
            .httpClient(client = httpClient)
            .build()
            .createWellKnownApi()
    }

    private val persistence: DataStore<AppConfig> by lazy {
        createAppConfigDataStorePersistence("librenostr_app_config.json")
    }

    private val appConfigDataStore: AppConfigDataStore by lazy {
        AppConfigDataStore(
            dispatcherProvider = dispatcherProvider,
            persistence = persistence,
        )
    }

    fun createAppConfigProvider(): AppConfigProvider {
        return DynamicAppConfigProvider(
            appConfigStore = appConfigDataStore,
            dispatcherProvider = dispatcherProvider,
        )
    }

    fun createAppConfigHandler(): AppConfigHandler {
        return AppConfigHandler(
            dispatcherProvider = dispatcherProvider,
            appConfigStore = appConfigDataStore,
            wellKnownApi = wellKnownApi,
        )
    }
}
