package net.primal.android.nostrconnect.connect

import net.primal.android.core.errors.UiError
import net.primal.android.drawer.multiaccount.model.UserAccountUi
import net.primal.domain.account.model.TrustLevel

interface NostrConnectContract {
    data class UiState(
        val appName: String?,
        val appDescription: String?,
        val appImageUrl: String?,
        val connectionUrl: String?,
        val callback: String? = null,
        val accounts: List<UserAccountUi> = emptyList(),
        val connecting: Boolean = false,
        val error: UiError? = null,
    )

    sealed class UiEvent {
        data class ConnectUser(
            val userId: String,
            val trustLevel: TrustLevel,
        ) : UiEvent()

        data object DismissError : UiEvent()
    }

    sealed class SideEffect {
        data class ConnectionSuccess(
            val callbackUri: String?,
            val userId: String,
        ) : SideEffect()
    }
}
