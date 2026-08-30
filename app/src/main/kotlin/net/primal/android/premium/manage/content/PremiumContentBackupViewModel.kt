package net.primal.android.premium.manage.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.premium.manage.content.PremiumContentBackupContract.UiEvent
import net.primal.android.premium.manage.content.PremiumContentBackupContract.UiState
import net.primal.android.premium.manage.content.model.ContentGroup
import net.primal.android.premium.manage.content.model.ContentType
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.onFailure
import net.primal.core.utils.onSuccess
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.premium.BroadcastingStatus
import net.primal.domain.premium.PremiumBroadcastRepository

@HiltViewModel
class PremiumContentBackupViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val premiumBroadcastRepository: PremiumBroadcastRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        fetchContentStats()
        fetchBroadcastStatus()
    }

    private fun fetchBroadcastStatus() {
        viewModelScope.launch {
            try {
                val status = premiumBroadcastRepository.fetchBroadcastStatus(
                    userId = activeAccountStore.activeUserId(),
                ).getOrThrow()
                handleBroadcastStatus(status)
            } catch (error: NetworkException) {
                Napier.e(throwable = error) { "Failed to fetch broadcast status due to network error." }
            }
        }
    }

    private fun fetchContentStats() {
        viewModelScope.launch {
            premiumBroadcastRepository.fetchContentStats(userId = activeAccountStore.activeUserId())
                .onSuccess { stats ->
                    setState {
                        copy(
                            contentTypes = ContentGroup.entries.map { group ->
                                ContentType(
                                    group = group,
                                    count = when (group) {
                                        ContentGroup.All -> stats.values.sum()
                                        else -> stats.filter { it.key in (group.kinds ?: emptyList()) }.values.sum()
                                    },
                                )
                            },
                        )
                    }
                }.onFailure { Napier.e(throwable = it) { "Failed to fetch content stats." } }
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect {
                when (it) {
                    UiEvent.StartBroadcastingMonitor -> startBroadcastMonitorIfStopped()
                    UiEvent.StopBroadcastingMonitor -> stopBroadcastMonitor()
                    UiEvent.StopBroadcasting -> stopBroadcasting()
                    is UiEvent.StartBroadcasting -> startBroadcasting(type = it.type)
                }
            }
        }
    }

    private fun handleBroadcastStatus(status: BroadcastingStatus) {
        setState {
            val newContentTypes = this.contentTypes.toMutableList().apply {
                val index = this.indexOfFirst { it.group.kinds == status.kinds }
                if (index != -1) {
                    this[index] = this[index].copy(broadcasting = status.running, progress = status.progress)
                }
            }
            copy(
                contentTypes = newContentTypes,
                anyBroadcasting = newContentTypes.any { it.broadcasting },
            )
        }
    }

    private fun startBroadcastMonitorIfStopped() {
        // No remote monitor exists: content rebroadcast is not a LibreNostr feature.
    }

    private fun stopBroadcastMonitor() {
        // No remote monitor exists: content rebroadcast is not a LibreNostr feature.
    }

    private fun startBroadcasting(type: ContentType) {
        viewModelScope.launch {
            premiumBroadcastRepository.startBroadcast(
                userId = activeAccountStore.activeUserId(),
                kinds = type.group.kinds,
            ).onSuccess {
                setState {
                    copy(
                        anyBroadcasting = true,
                        contentTypes = this.contentTypes.toMutableList().apply {
                            val index = this.indexOf(type)
                            if (index != -1) {
                                this[index] = this[index].copy(broadcasting = true, progress = 0.01f)
                            }
                        },
                    )
                }
            }.onFailure { Napier.e(throwable = it) { "Failed to start broadcasting." } }
        }
    }

    private fun stopBroadcasting() {
        viewModelScope.launch {
            premiumBroadcastRepository.cancelBroadcast(userId = activeAccountStore.activeUserId())
                .onFailure { Napier.e(throwable = it) { "Failed to stop broadcasting." } }
        }
    }
}
