package net.primal.android.settings.network

data class SocketDestinationUiState(
    val url: String = "",
    val connected: Boolean = false,
    val read: Boolean = true,
    val write: Boolean = true,
)
