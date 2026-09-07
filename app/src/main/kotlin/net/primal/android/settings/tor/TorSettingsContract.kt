package net.primal.android.settings.tor

import net.primal.core.networking.tor.DEFAULT_ORBOT_SOCKS_PORT

interface TorSettingsContract {
    data class UiState(
        val torEnabled: Boolean = false,
        val torSocksPortText: String = DEFAULT_ORBOT_SOCKS_PORT.toString(),
        val torPortInvalid: Boolean = false,
        val orbotInstalled: Boolean = true,
        val showRestartNotice: Boolean = false,
    )

    sealed class UiEvent {
        data class ToggleTor(val enabled: Boolean) : UiEvent()
        data class UpdateTorPort(val rawPort: String) : UiEvent()
        data object DismissRestartNotice : UiEvent()
        data object RecheckOrbotInstalled : UiEvent()
    }
}
