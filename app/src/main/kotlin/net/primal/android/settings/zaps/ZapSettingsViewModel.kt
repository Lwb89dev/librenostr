package net.primal.android.settings.zaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import net.primal.android.settings.repository.SettingsRepository
import net.primal.android.settings.zaps.ZapSettingsContract.UiEvent
import net.primal.android.settings.zaps.ZapSettingsContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.notifications.ContentZapConfigItem
import net.primal.domain.notifications.ContentZapDefault

@HiltViewModel
class ZapSettingsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        observeActiveAccount()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.EditZapDefault -> {
                        setState { copy(editPresetIndex = -1) }
                    }

                    is UiEvent.EditZapPreset -> {
                        val index = _state.value.zapConfig.indexOf(it.preset)
                        setState { copy(editPresetIndex = index) }
                    }

                    UiEvent.CloseEditor -> {
                        setState { copy(editPresetIndex = null) }
                    }

                    is UiEvent.UpdateZapPreset -> {
                        updateZapPreset(presetIndex = it.index, zapPreset = it.zapPreset)
                    }

                    is UiEvent.UpdateZapDefault -> {
                        updateDefaultZapAmount(newZapDefault = it.newZapDefault)
                    }
                }
            }
        }

    private fun observeActiveAccount() =
        viewModelScope.launch {
            activeAccountStore.activeUserAccount
                .mapNotNull { it.appSettings }
                .collect {
                    setState {
                        copy(zapDefault = it.zapDefault, zapConfig = it.zapsConfig)
                    }
                }
        }

    private fun updateDefaultZapAmount(newZapDefault: ContentZapDefault) =
        viewModelScope.launch {
            setState { copy(saving = true) }
            try {
                settingsRepository.updateAppSettingsLocally(activeAccountStore.activeUserId()) {
                    copy(zapDefault = newZapDefault)
                }
                setState { copy(editPresetIndex = null) }
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { "Failed to update default zap amount." }
            } finally {
                setState { copy(saving = false) }
            }
        }

    private fun updateZapPreset(presetIndex: Int, zapPreset: ContentZapConfigItem) =
        viewModelScope.launch {
            setState { copy(saving = true) }
            try {
                settingsRepository.updateAppSettingsLocally(activeAccountStore.activeUserId()) {
                    copy(
                        zapsConfig = zapsConfig.toMutableList().apply {
                            this[presetIndex] = zapPreset
                        },
                    )
                }
                setState { copy(editPresetIndex = null) }
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { "Failed to update zap preset." }
            } finally {
                setState { copy(saving = false) }
            }
        }
}
