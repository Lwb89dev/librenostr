package net.primal.android.settings.network

interface NetworkSettingsContract {
    data class UiState(
        val relays: List<SocketDestinationUiState> = emptyList(),
        val updatingRelays: Boolean = false,
        val newRelayUrl: String = "",
        val error: NetworkSettingsError? = null,
    ) {
        sealed class NetworkSettingsError {
            data class FailedToAddRelay(val cause: Throwable?) : NetworkSettingsError()
        }
    }

    sealed class UiEvent {
        data object RestoreDefaultRelays : UiEvent()
        data object DismissError : UiEvent()
        data class DeleteRelay(val url: String) : UiEvent()
        data class ConfirmRelayInsert(val url: String) : UiEvent()
        data class UpdateNewRelayUrl(val url: String) : UiEvent()
        data class UpdateRelayRead(val url: String, val read: Boolean) : UiEvent()
        data class UpdateRelayWrite(val url: String, val write: Boolean) : UiEvent()
    }
}
