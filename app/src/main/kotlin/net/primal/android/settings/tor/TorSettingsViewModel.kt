package net.primal.android.settings.tor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.settings.tor.TorSettingsContract.UiEvent
import net.primal.android.settings.tor.TorSettingsContract.UiState
import net.primal.core.networking.tor.isValidSocksPort

@HiltViewModel
class TorSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val torProxySettingsRepository: TorProxySettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _uiState.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        observeTorSettings()
        recheckOrbotInstalled()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.ToggleTor -> toggleTor(enabled = it.enabled)
                    is UiEvent.UpdateTorPort -> updateTorPort(rawPort = it.rawPort)
                    UiEvent.DismissRestartNotice -> setState { copy(showRestartNotice = false) }
                    UiEvent.RecheckOrbotInstalled -> recheckOrbotInstalled()
                }
            }
        }

    private fun observeTorSettings() =
        viewModelScope.launch {
            torProxySettingsRepository.settings.collect { settings ->
                setState {
                    copy(
                        torEnabled = settings.enabled,
                        torSocksPortText = settings.socksPort.toString(),
                    )
                }
            }
        }

    private fun recheckOrbotInstalled() {
        setState { copy(orbotInstalled = isOrbotInstalled(context)) }
    }

    private fun toggleTor(enabled: Boolean) =
        viewModelScope.launch {
            torProxySettingsRepository.setEnabled(enabled)
            setState { copy(showRestartNotice = true) }
        }

    private fun updateTorPort(rawPort: String) =
        viewModelScope.launch {
            setState { copy(torSocksPortText = rawPort) }
            val port = rawPort.toIntOrNull()
            if (port != null && port.isValidSocksPort()) {
                torProxySettingsRepository.setPort(port)
                setState { copy(torPortInvalid = false, showRestartNotice = true) }
            } else {
                setState { copy(torPortInvalid = true) }
            }
        }
}
