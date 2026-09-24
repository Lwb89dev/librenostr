package net.primal.android.settings.tor

import net.primal.core.networking.tor.DEFAULT_ORBOT_SOCKS_PORT
import net.primal.core.networking.tor.TorEngineType
import net.primal.core.networking.tor.engine.TorEngineState

interface TorSettingsContract {
    data class UiState(
        val torEnabled: Boolean = false,
        val torSocksPortText: String = DEFAULT_ORBOT_SOCKS_PORT.toString(),
        val torPortInvalid: Boolean = false,
        val orbotInstalled: Boolean = true,
        val showRestartNotice: Boolean = false,
        val torEngine: TorEngineType = TorEngineType.ORBOT,
        /** What the in-app Tor client is doing right now; only meaningful for the built-in engine. */
        val builtInState: TorEngineState = TorEngineState.Off,
        /** False when this build has no native Tor library (or it does not load on this device). */
        val builtInAvailable: Boolean = true,
    )

    sealed class UiEvent {
        data class ToggleTor(val enabled: Boolean) : UiEvent()
        data class UpdateTorPort(val rawPort: String) : UiEvent()
        data class SelectTorEngine(val engine: TorEngineType) : UiEvent()
        data object DismissRestartNotice : UiEvent()
        data object RecheckOrbotInstalled : UiEvent()
    }
}
