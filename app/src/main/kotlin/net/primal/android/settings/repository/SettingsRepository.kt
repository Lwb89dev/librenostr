package net.primal.android.settings.repository

import javax.inject.Inject
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.domain.UserAccount
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.global.ContentAppSettings
import net.primal.domain.notifications.DEFAULT_ZAP_CONFIG
import net.primal.domain.notifications.DEFAULT_ZAP_DEFAULT

class SettingsRepository @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val accountsStore: UserAccountsStore,
) {

    suspend fun updateAppSettingsLocally(
        userId: String,
        transform: ContentAppSettings.() -> ContentAppSettings,
    ) {
        withContext(dispatcherProvider.io()) {
            val current = accountsStore.findByIdOrNull(userId)?.appSettings ?: defaultLocalAppSettings()
            persistAppSettingsLocally(userId = userId, appSettings = current.transform())
        }
    }

    private suspend fun persistAppSettingsLocally(userId: String, appSettings: ContentAppSettings) {
        val currentUserAccount = accountsStore.findByIdOrNull(userId = userId)
            ?: UserAccount.buildLocal(pubkey = userId)

        accountsStore.upsertAccount(userAccount = currentUserAccount.copy(appSettings = appSettings))
    }

    private fun defaultLocalAppSettings(): ContentAppSettings =
        ContentAppSettings(
            notifications = buildJsonObject {},
            zapDefault = DEFAULT_ZAP_DEFAULT,
            zapsConfig = DEFAULT_ZAP_CONFIG,
        )

}
