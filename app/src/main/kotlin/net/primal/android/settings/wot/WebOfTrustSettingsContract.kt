package net.primal.android.settings.wot

import net.primal.domain.wot.WotDiscoveryState

interface WebOfTrustSettingsContract {
    data class UiState(
        val filterEnabled: Boolean = false,
        val discoveryState: WotDiscoveryState = WotDiscoveryState.Idle,
    )

    sealed class UiEvent {
        data class ToggleFilter(val enabled: Boolean) : UiEvent()
        data object RefreshNetworkClick : UiEvent()
    }
}
