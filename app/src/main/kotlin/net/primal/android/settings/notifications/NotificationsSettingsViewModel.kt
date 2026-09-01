package net.primal.android.settings.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.primal.android.core.push.PushNotificationsTokenUpdater
import net.primal.android.settings.notifications.NotificationsSettingsContract.UiEvent.DismissErrors
import net.primal.android.settings.notifications.NotificationsSettingsContract.UiEvent.NotificationSettingsChanged
import net.primal.android.settings.notifications.NotificationsSettingsContract.UiState
import net.primal.android.settings.notifications.NotificationsSettingsContract.UiState.ApiError.UpdateAppSettingsError
import net.primal.android.settings.notifications.ui.NotificationSwitchUi
import net.primal.android.settings.notifications.ui.mapAsNotificationsPreferences
import net.primal.android.settings.notifications.ui.mapAsPushNotificationSwitchUi
import net.primal.android.settings.notifications.ui.mapAsTabNotificationSwitchUi
import net.primal.android.settings.repository.SettingsRepository
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.notifications.NotificationSettingsType
import net.primal.domain.notifications.NotificationSettingsType.Preferences
import net.primal.domain.notifications.NotificationSettingsType.PushNotifications
import net.primal.domain.notifications.NotificationSettingsType.TabNotifications

@HiltViewModel
class NotificationsSettingsViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val settingsRepository: SettingsRepository,
    private val activeAccountStore: ActiveAccountStore,
    private val pushNotificationsTokenUpdater: PushNotificationsTokenUpdater,
    private val accountsStore: UserAccountsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) {
        _state.getAndUpdate { it.reducer() }
    }

    private val events: MutableSharedFlow<NotificationsSettingsContract.UiEvent> = MutableSharedFlow()
    fun setEvent(event: NotificationsSettingsContract.UiEvent) =
        viewModelScope.launch {
            events.emit(event)
        }

    init {
        observeEvents()
        observeActiveAccount()
        observeDebouncedNotificationSettingsChanges()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    DismissErrors -> setState { copy(error = null) }
                    is NotificationSettingsChanged -> {
                        when (event.type) {
                            is Preferences -> setState {
                                copy(preferencesSettings = this.preferencesSettings.update(event))
                            }

                            is PushNotifications -> setState {
                                copy(pushNotificationsSettings = this.pushNotificationsSettings.update(event))
                            }

                            is TabNotifications -> setState {
                                copy(tabNotificationsSettings = this.tabNotificationsSettings.update(event))
                            }
                        }
                    }

                    is NotificationsSettingsContract.UiEvent.PushNotificationsToggled ->
                        updatePushNotificationsEnabled(event.value)

                    is NotificationsSettingsContract.UiEvent.FollowNotificationsToggled ->
                        updateShowFollowNotifications(event.value)
                }
            }
        }

    private fun updatePushNotificationsEnabled(value: Boolean) =
        viewModelScope.launch {
            withContext(dispatcherProvider.io()) {
                accountsStore.getAndUpdateAccount(activeAccountStore.activeUserId()) {
                    copy(pushNotificationsEnabled = value)
                }

                pushNotificationsTokenUpdater.updateTokenForAllUsers()
            }
        }

    private fun updateShowFollowNotifications(value: Boolean) =
        viewModelScope.launch {
            withContext(dispatcherProvider.io()) {
                accountsStore.getAndUpdateAccount(activeAccountStore.activeUserId()) {
                    copy(showFollowNotifications = value)
                }
            }
        }

    private fun <T : NotificationSettingsType> List<NotificationSwitchUi<T>>.update(
        event: NotificationSettingsChanged,
    ): List<NotificationSwitchUi<T>> {
        return this.toMutableList().apply {
            val existingSetting = first { it.settingsType == event.type }
            val existingSettingIndex = indexOf(existingSetting)
            this[existingSettingIndex] = existingSetting.copy(enabled = event.value)
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeDebouncedNotificationSettingsChanges() =
        viewModelScope.launch {
            events.filterIsInstance<NotificationSettingsChanged>()
                .debounce(1.seconds)
                .collect {
                    updateNotificationsSettings(
                        tabNotificationsSettings = state.value.tabNotificationsSettings,
                        pushNotificationsSettings = state.value.pushNotificationsSettings,
                        preferencesSettings = state.value.preferencesSettings,
                    )
                }
        }

    private fun observeActiveAccount() =
        viewModelScope.launch {
            activeAccountStore.activeUserAccount
                .collect { activeAccount ->
                    setState {
                        copy(
                            pushNotificationsEnabled = activeAccount.pushNotificationsEnabled,
                            showFollowNotifications = activeAccount.showFollowNotifications,
                            pushNotificationsSettings = activeAccount.appSettings?.mapAsPushNotificationSwitchUi()
                                ?: emptyList(),
                            tabNotificationsSettings = activeAccount.appSettings?.mapAsTabNotificationSwitchUi()
                                ?: emptyList(),
                            preferencesSettings = activeAccount.appSettings?.mapAsNotificationsPreferences()
                                ?: emptyList(),
                        )
                    }
                }
        }

    private fun updateNotificationsSettings(
        tabNotificationsSettings: List<NotificationSwitchUi<TabNotifications>>,
        pushNotificationsSettings: List<NotificationSwitchUi<PushNotifications>>,
        preferencesSettings: List<NotificationSwitchUi<Preferences>>,
    ) = viewModelScope.launch {
        val tabNotificationsJsonObject = tabNotificationsSettings.mapToRemoteTabNotificationsJsonObject()
        val pushNotificationsJsonObject = pushNotificationsSettings.mapToRemoteJsonObject()
        val preferencesJsonObject = preferencesSettings.mapToRemoteJsonObject()

        try {
            settingsRepository.updateAppSettingsLocally(activeAccountStore.activeUserId()) {
                copy(
                    notifications = tabNotificationsJsonObject,
                    pushNotifications = pushNotificationsJsonObject,
                    notificationsAdditional = preferencesJsonObject,
                )
            }
        } catch (error: NetworkException) {
            setState { copy(error = UpdateAppSettingsError(cause = error)) }
        }
    }

    private fun List<NotificationSwitchUi<TabNotifications>>.mapToRemoteTabNotificationsJsonObject(): JsonObject {
        return JsonObject(
            content = this.flatMap { switchUi ->
                switchUi.settingsType.types.map { it.id to JsonPrimitive(switchUi.enabled) }
            }.toMap(),
        )
    }

    private fun <T : NotificationSettingsType> List<NotificationSwitchUi<T>>.mapToRemoteJsonObject(): JsonObject {
        return JsonObject(
            content = this.associate {
                it.settingsType.id to JsonPrimitive(value = it.enabled)
            },
        )
    }
}
