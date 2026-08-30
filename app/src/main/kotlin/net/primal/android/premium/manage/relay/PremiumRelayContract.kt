package net.primal.android.premium.manage.relay

interface PremiumRelayContract {
    data class UiState(
        val relayUrl: String = "wss://relay.damus.io",
        val isConnected: Boolean = false,
        val version: String = "strfry.git version 1.0.2",
        val addingRelay: Boolean = false,
        val error: Throwable? = null,
    )

    sealed class UiEvent {
        data object ConnectToRelay : UiEvent()
        data object DismissError : UiEvent()
    }
}
