package net.primal.android.settings.wot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.settings.wot.WebOfTrustSettingsContract.UiEvent
import net.primal.android.settings.wot.WebOfTrustSettingsContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.wot.WebOfTrustRepository

@HiltViewModel
class WebOfTrustSettingsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val webOfTrustRepository: WebOfTrustRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _uiState.getAndUpdate { it.reducer() }

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        observeFilterEnabled()
        observeDiscoveryState()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.ToggleFilter -> toggleFilter(enabled = it.enabled)
                    UiEvent.RefreshNetworkClick -> refreshNetwork()
                }
            }
        }

    private fun observeFilterEnabled() =
        viewModelScope.launch {
            webOfTrustRepository.observeFilterEnabled(ownerId = activeAccountStore.activeUserId())
                .collect { enabled -> setState { copy(filterEnabled = enabled) } }
        }

    private fun observeDiscoveryState() =
        viewModelScope.launch {
            webOfTrustRepository.observeDiscoveryState(ownerId = activeAccountStore.activeUserId())
                .collect { discoveryState -> setState { copy(discoveryState = discoveryState) } }
        }

    private fun toggleFilter(enabled: Boolean) =
        viewModelScope.launch {
            webOfTrustRepository.setFilterEnabled(ownerId = activeAccountStore.activeUserId(), enabled = enabled)
            // Turning the filter on is the natural moment to also compute a network for the first
            // time; without this the toggle would just sit there doing nothing until the user found
            // the refresh button on their own, having no way to know it was needed at all.
            if (enabled) refreshNetwork()
        }

    private fun refreshNetwork() =
        viewModelScope.launch {
            val account = activeAccountStore.activeUserAccount()
            webOfTrustRepository.refreshNetwork(ownerId = account.pubkey, firstDegreeFollows = account.following)
        }
}
