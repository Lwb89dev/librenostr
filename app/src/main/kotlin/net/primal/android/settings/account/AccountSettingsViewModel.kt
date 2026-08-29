package net.primal.android.settings.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.premium.legend.domain.asLegendaryCustomization
import net.primal.android.settings.account.AccountSettingsContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.UserAccount
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    init {
        observeActiveAccount()
    }

    private fun observeActiveAccount() =
        viewModelScope.launch {
            activeAccountStore.activeUserAccount.collect { account ->
                setState { uiStateFor(account) }
            }
        }

    private fun uiStateFor(account: UserAccount): UiState {
        val pubkeyHex = account.pubkey.ifBlank { activeAccountStore.activeUserId() }
        val npub = pubkeyHex.toNpubOrNull()
        val credential = npub?.let(credentialsStore::find)
        return UiState(
            avatarCdnImage = account.avatarCdnImage,
            nsec = credential?.nsec,
            npub = credential?.npub ?: npub.orEmpty(),
            legendaryCustomization = account.primalLegendProfile?.asLegendaryCustomization(),
        )
    }

    private fun String.toNpubOrNull(): String? {
        if (isBlank()) return null
        return runCatching { hexToNpubHrp() }.getOrNull()
    }
}
