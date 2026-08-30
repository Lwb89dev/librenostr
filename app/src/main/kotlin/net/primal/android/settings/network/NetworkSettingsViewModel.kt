package net.primal.android.settings.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.settings.network.NetworkSettingsContract.UiEvent
import net.primal.android.settings.network.NetworkSettingsContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.repository.RelayRepository
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.cryptography.SignatureException

@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val relaysSocketManager: RelaysSocketManager,
    private val relayRepository: RelayRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _uiState.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private var latestRelaysPoolStatus = emptyMap<String, Boolean>()

    init {
        observeEvents()
        ensureRelayPoolUpdatedAndConnected()
        observeRelayPoolConnections()
        observeUserRelays()
    }

    private fun observeUserRelays() =
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            relayRepository.observeUserRelays(userId).collect { relays ->
                setState {
                    copy(
                        relays = relays.distinctBy { it.url }.map { relay ->
                            SocketDestinationUiState(
                                url = relay.url,
                                connected = latestRelaysPoolStatus[relay.url] ?: false,
                                read = relay.read,
                                write = relay.write,
                            )
                        },
                    )
                }
            }
        }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.RestoreDefaultRelays -> restoreDefaultRelays()
                    is UiEvent.DeleteRelay -> deleteRelay(url = it.url)
                    is UiEvent.ConfirmRelayInsert -> addRelay(url = it.url)
                    is UiEvent.UpdateNewRelayUrl -> setState { copy(newRelayUrl = it.url) }
                    is UiEvent.UpdateRelayRead -> updateRelayPermissions(url = it.url, read = it.read, write = null)
                    is UiEvent.UpdateRelayWrite -> updateRelayPermissions(url = it.url, read = null, write = it.write)
                    UiEvent.DismissError -> setState { copy(error = null) }
                }
            }
        }

    private fun ensureRelayPoolUpdatedAndConnected() =
        viewModelScope.launch {
            runCatching {
                relayRepository.syncUserRelaysOrBootstrap(userId = activeAccountStore.activeUserId())
            }.onFailure { error ->
                Napier.w(throwable = error) { "Failed to fetch and update user relays." }
            }
            delay(1.seconds)
            relaysSocketManager.tryConnectingToAllUserRelays()
        }

    private fun ensureRelayConnected(url: String) =
        viewModelScope.launch {
            delay(1.seconds)
            relaysSocketManager.tryConnectingToUserRelay(url)
        }

    private fun observeRelayPoolConnections() =
        viewModelScope.launch {
            relaysSocketManager.userRelayPoolStatus.collect { poolStatus ->
                latestRelaysPoolStatus = poolStatus
                setState {
                    copy(
                        relays = this.relays.toMutableList().apply {
                            forEachIndexed { index, relay ->
                                this[index] = relay.copy(connected = poolStatus[relay.url] ?: false)
                            }
                        },
                    )
                }
            }
        }

    private fun restoreDefaultRelays() =
        viewModelScope.launch {
            changeRelayList { userId ->
                try {
                    relayRepository.bootstrapDefaultUserRelays(userId = userId)
                } catch (error: SignatureException) {
                    Napier.w(throwable = error) { "Failed to restore default relays due to signature error." }
                } catch (error: NostrPublishException) {
                    Napier.w(throwable = error) { "Failed to restore default relays due to nostr publish error." }
                }
                ensureRelayPoolUpdatedAndConnected()
            }
        }

    private fun deleteRelay(url: String) =
        viewModelScope.launch {
            changeRelayList { userId ->
                relayRepository.removeRelayAndPublishRelayList(userId = userId, url = url)
            }
        }

    private fun addRelay(url: String) =
        viewModelScope.launch {
            changeRelayList { userId ->
                relayRepository.addRelayAndPublishRelayList(userId = userId, url = url)
                ensureRelayConnected(url)
                setState { copy(newRelayUrl = "") }
            }
        }

    private fun updateRelayPermissions(url: String, read: Boolean?, write: Boolean?) =
        viewModelScope.launch {
            val current = _uiState.value.relays.find { it.url == url } ?: return@launch
            val newRead = read ?: current.read
            val newWrite = write ?: current.write
            changeRelayList { userId ->
                relayRepository.updateRelayPermissionsAndPublishRelayList(
                    userId = userId,
                    url = url,
                    read = newRead,
                    write = newWrite,
                )
            }
        }

    private suspend fun changeRelayList(block: suspend (String) -> Unit) {
        try {
            setState { copy(updatingRelays = true) }
            val userId = activeAccountStore.activeUserId()
            block(userId)
        } catch (error: NetworkException) {
            Napier.w(throwable = error) { "Failed to change relay list due to network error." }
            setState { copy(error = UiState.NetworkSettingsError.FailedToAddRelay(error)) }
        } catch (error: SignatureException) {
            Napier.w(throwable = error) { "Failed to change relay list due to signature error." }
            setState { copy(error = UiState.NetworkSettingsError.FailedToAddRelay(error)) }
        } catch (error: NostrPublishException) {
            Napier.w(throwable = error) { "Failed to change relay list due to nostr publish error." }
            setState { copy(error = UiState.NetworkSettingsError.FailedToAddRelay(error)) }
        } finally {
            setState { copy(updatingRelays = false) }
        }
    }
}
