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
import net.primal.core.networking.tor.TorEngineType
import net.primal.core.networking.tor.engine.BuiltInTor
import net.primal.core.networking.tor.isValidSocksPort
import net.primal.core.utils.coroutines.DispatcherProvider

@HiltViewModel
class TorSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
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
        observeBuiltInTor()
        recheckOrbotInstalled()
        probeBuiltInAvailability()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.ToggleTor -> toggleTor(enabled = it.enabled)
                    is UiEvent.UpdateTorPort -> updateTorPort(rawPort = it.rawPort)
                    is UiEvent.SelectTorEngine -> selectTorEngine(engine = it.engine)
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
                        torEngine = settings.engine,
                        torSocksPortText = settings.socksPort.toString(),
                    )
                }
            }
        }

    private fun observeBuiltInTor() =
        viewModelScope.launch {
            BuiltInTor.state.collect { state -> setState { copy(builtInState = state) } }
        }

    /** Loading the native library is blocking, so the check runs off the main thread. */
    private fun probeBuiltInAvailability() =
        viewModelScope.launch(dispatchers.io()) {
            val available = BuiltInTor.isLibraryAvailable()
            setState { copy(builtInAvailable = available) }
        }

    private fun recheckOrbotInstalled() {
        setState { copy(orbotInstalled = isOrbotInstalled(context)) }
    }

    private fun toggleTor(enabled: Boolean) =
        viewModelScope.launch {
            torProxySettingsRepository.setEnabled(enabled)
            applyEngineChoice(enabled = enabled, engine = uiState.value.torEngine)
            setState { copy(showRestartNotice = true) }
        }

    private fun selectTorEngine(engine: TorEngineType) =
        viewModelScope.launch {
            torProxySettingsRepository.setEngine(engine)
            applyEngineChoice(enabled = uiState.value.torEnabled, engine = engine)
            setState { copy(showRestartNotice = true) }
        }

    /**
     * Starts the built-in client as soon as it is chosen, and stops it when it is not wanted, so its
     * status shows up here and its first download is already done by the time the app is restarted.
     * Traffic only starts flowing through it after that restart, like every other Tor setting.
     */
    private fun applyEngineChoice(enabled: Boolean, engine: TorEngineType) {
        if (enabled && engine == TorEngineType.BUILT_IN) {
            BuiltInTor.startAsync(context)
        } else {
            BuiltInTor.stopAsync()
        }
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
