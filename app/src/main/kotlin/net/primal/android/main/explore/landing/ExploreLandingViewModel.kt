package net.primal.android.main.explore.landing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.main.explore.landing.ExploreLandingContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.repository.UserRepository

@HiltViewModel
class ExploreLandingViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    init {
        observeRecentUsers()
        observeRecentSearches()
    }

    private fun observeRecentUsers() =
        viewModelScope.launch {
            userRepository.observeRecentUsers(ownerId = activeAccountStore.activeUserId())
                .collect { users ->
                    setState { copy(recentUsers = users) }
                }
        }

    private fun observeRecentSearches() =
        viewModelScope.launch {
            userRepository.observeRecentSearches(
                ownerId = activeAccountStore.activeUserId(),
                limit = MAX_RECENT_SEARCHES,
            )
                .collect { queries ->
                    setState { copy(recentSearches = queries, recentSearchesLoading = false) }
                }
        }

    companion object {
        private const val MAX_RECENT_SEARCHES = 5
    }
}
