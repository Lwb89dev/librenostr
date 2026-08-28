package net.primal.android.core.compose.connectionindicator

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.core.compose.connectionindicator.ConnectionIndicatorContract.UiState
import net.primal.android.networking.relays.RelaysSocketManager

@HiltViewModel
class ConnectionIndicatorViewModel @Inject constructor(
    private val relaysSocketManager: RelaysSocketManager,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    init {
        observeRelayConnections()
    }

    private fun observeRelayConnections() =
        viewModelScope.launch {
            combine(
                relaysSocketManager.userRelayPoolStatus,
                relaysSocketManager.fallbackRelayPoolStatus,
            ) { user, fallback ->
                user.values.any { it } || fallback.values.any { it }
            }
                .flowWithLifecycle(ProcessLifecycleOwner.get().lifecycle)
                .collect { connected ->
                    setState { copy(hasConnection = connected) }
                }
        }
}
