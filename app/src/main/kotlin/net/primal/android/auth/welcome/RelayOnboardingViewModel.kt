package net.primal.android.auth.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.networking.relays.ONBOARDING_RELAY_OPTIONS
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.repository.RelayRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrElse
import net.primal.core.utils.runCatching

@HiltViewModel
class RelayOnboardingViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val relayRepository: RelayRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(RelayOnboardingState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            val suggestions = withContext(dispatchers.io()) {
                runCatching {
                    relayRepository.syncUserRelaysOrBootstrap(userId)
                    val configured = relayRepository.findRelays(userId, RelayKind.UserRelay)
                        .map { it.url }
                    (configured + ONBOARDING_RELAY_OPTIONS.map { it.url }).distinct()
                }.getOrElse { error ->
                    Napier.w(error) { "Unable to load relay onboarding suggestions; using defaults." }
                    ONBOARDING_RELAY_OPTIONS.map { it.url }
                }
            }
            _state.value = RelayOnboardingState(
                suggestions = suggestions,
                // Left empty deliberately: see RelayOnboardingState.
                selected = emptySet(),
                loading = false,
            )
        }
    }

    fun toggleRelay(url: String) {
        _state.value = _state.value.copy(
            selected = _state.value.selected.toMutableSet().apply {
                if (!add(url)) remove(url)
            },
        )
    }

    fun addRelay(url: String) {
        val normalized = url.trim().lowercase()
            .let { value ->
                when {
                    value.startsWith("wss://") -> value
                    value.startsWith("ws://") -> "wss://${value.removePrefix("ws://")}"
                    else -> "wss://$value"
                }
            }
            .removeSuffix("/")
        if (!normalized.removePrefix("wss://").contains('.')) return
        val current = _state.value.suggestions
        if (normalized in current) return
        _state.value = _state.value.copy(
            suggestions = current + normalized,
            selected = _state.value.selected + normalized,
        )
    }

    fun save(onComplete: () -> Unit) {
        val selected = _state.value.selected.toList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            runCatching {
                relayRepository.bootstrapUserRelays(activeAccountStore.activeUserId(), selected)
            }
            _state.value = _state.value.copy(saving = false)
            onComplete()
        }
    }
}

data class RelayOnboardingState(
    val suggestions: List<String> = ONBOARDING_RELAY_OPTIONS.map { it.url },
    // Nothing is ticked by default. A pre-ticked list reads like an endorsement, and which
    // relays someone talks to is the one choice this app should never make for them.
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val saving: Boolean = false,
)
