package net.primal.android.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.primal.android.core.updater.DataUpdater
import net.primal.android.core.updater.SessionSyncCoordinator
import net.primal.android.main.MainContract.UiEvent
import net.primal.android.main.MainContract.UiState
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.repository.UserRepository
import net.primal.android.user.subscriptions.SubscriptionsManager
import net.primal.domain.messages.ChatRepository
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationRepository

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel @Inject constructor(
    private val dataUpdater: DataUpdater,
    // Injected so the singleton is constructed and starts observing the active account; it has
    // no methods to call, it reacts on its own.
    @Suppress("unused") private val sessionSyncCoordinator: SessionSyncCoordinator,
    private val activeAccountStore: ActiveAccountStore,
    private val accountsStore: UserAccountsStore,
    private val userRepository: UserRepository,
    private val subscriptionsManager: SubscriptionsManager,
    private val chatRepository: ChatRepository,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    private var notificationsMarkedSeen = false
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private val _effects = Channel<MainContract.SideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: MainContract.SideEffect) = viewModelScope.launch { _effects.send(effect) }

    init {
        observeEvents()
        observeActiveAccount()
        observeUserAccounts()
        observeBadgesUpdates()
        observeLocalUnreadBadges()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.RequestUserDataUpdate -> dataUpdater.updateData()
                    UiEvent.SwitchToNextAccount -> switchToNextAccount()
                    UiEvent.DismissExploreHint -> dismissExploreHint()
                    UiEvent.NotificationsViewed -> setState {
                        notificationsMarkedSeen = true
                        copy(badges = badges.copy(unreadNotificationsCount = 0))
                    }
                }
            }
        }

    private fun observeActiveAccount() =
        viewModelScope.launch {
            activeAccountStore.activeUserAccount.collect {
                setState {
                    copy(
                        activeAccountId = it.pubkey,
                        activeAccountAvatarCdnImage = it.avatarCdnImage,
                        activeAccountBlossoms = it.blossomServers,
                        showExploreHint = it.shouldShowExploreHint,
                    )
                }
            }
        }

    private fun dismissExploreHint() =
        viewModelScope.launch {
            accountsStore.getAndUpdateAccount(userId = activeAccountStore.activeUserId()) {
                copy(shouldShowExploreHint = false)
            }
        }

    private fun observeUserAccounts() =
        viewModelScope.launch {
            accountsStore.userAccounts.collect { accounts ->
                setState { copy(hasMultipleAccounts = accounts.size > 1) }
            }
        }

    private fun switchToNextAccount() =
        viewModelScope.launch {
            val activeUserId = activeAccountStore.activeUserId()
            val nextAccount = accountsStore.userAccounts.value
                .filter { it.pubkey != activeUserId }
                .maxByOrNull { it.lastAccessedAt }
                ?: return@launch
            userRepository.setActiveAccount(userId = nextAccount.pubkey)
            setEffect(MainContract.SideEffect.AccountSwitched)
        }

    private fun observeBadgesUpdates() =
        viewModelScope.launch {
            subscriptionsManager.badges.collect {
                setState {
                    copy(badges = if (notificationsMarkedSeen) it.copy(unreadNotificationsCount = 0) else it)
                }
            }
        }

    private fun observeLocalUnreadBadges() =
        viewModelScope.launch {
            activeAccountStore.activeUserId
                .flatMapLatest { userId ->
                    if (userId.isBlank()) {
                        flowOf(0 to 0)
                    } else {
                        combine(
                            chatRepository.observeUnreadMessagesCount(userId),
                            notificationRepository.observeUnseenNotifications(
                                ownerId = userId,
                                group = NotificationGroup.ALL,
                            ).map { it.size },
                        ) { unreadMessages, unreadNotifications ->
                            unreadMessages to unreadNotifications
                        }
                    }
                }
                .collect { (unreadMessages, unreadNotifications) ->
                    setState {
                        copy(
                            badges = badges.copy(
                                unreadMessagesCount = unreadMessages,
                                unreadNotificationsCount = if (notificationsMarkedSeen) 0 else unreadNotifications,
                            ),
                        )
                    }
                }
        }
}
