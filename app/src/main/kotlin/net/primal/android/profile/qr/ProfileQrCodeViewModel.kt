package net.primal.android.profile.qr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.primal.android.core.compose.profile.model.asProfileDetailsUi
import net.primal.android.navigation.profileId
import net.primal.android.profile.qr.ProfileQrCodeContract.SideEffect
import net.primal.android.profile.qr.ProfileQrCodeContract.UiEvent
import net.primal.android.profile.qr.ProfileQrCodeContract.UiState
import net.primal.android.scanner.domain.QrCodeDataType
import net.primal.android.scanner.domain.QrCodeResult
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.bech32ToHexOrNull
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.nostr.utils.extractProfileId
import net.primal.domain.nostr.utils.takeAsNaddrOrNull
import net.primal.domain.nostr.utils.takeAsNaddrStringOrNull
import net.primal.domain.profile.ProfileRepository

@HiltViewModel
class ProfileQrCodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val activeAccountStore: ActiveAccountStore,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val profileId: String = savedStateHandle.profileId ?: activeAccountStore.activeUserId()

    private val _state = MutableStateFlow(UiState(profileId = profileId))
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private val _effects = Channel<SideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: SideEffect) = viewModelScope.launch { _effects.send(effect) }

    init {
        observeEvents()
        observeProfileData()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.ProcessQrCodeResult -> processQrCodeResult(result = it.result)
                }
            }
        }

    private fun observeProfileData() =
        viewModelScope.launch {
            profileRepository.observeProfileData(profileId = profileId)
                .distinctUntilChanged()
                .collect { profileData ->
                    setState { copy(profileDetails = profileData.asProfileDetailsUi()) }
                }
        }

    @Suppress("CyclomaticComplexMethod")
    private fun processQrCodeResult(result: QrCodeResult) =
        viewModelScope.launch {
            when (result.type) {
                QrCodeDataType.NPUB -> result.value.bech32ToHexOrNull()?.let { processProfileId(profileId = it) }

                QrCodeDataType.NPUB_URI,
                QrCodeDataType.NPROFILE_URI,
                QrCodeDataType.NPROFILE,
                -> result.value.extractProfileId()?.let { processProfileId(profileId = it) }

                QrCodeDataType.NADDR -> processNaddr(naddr = result.value)
                QrCodeDataType.NADDR_URI -> result.value.takeAsNaddrStringOrNull()?.let { processNaddr(naddr = it) }

                QrCodeDataType.NEVENT -> result.value.bech32ToHexOrNull()?.let { processNoteId(noteId = it) }
                QrCodeDataType.NEVENT_URI -> result.value.extractNoteId()?.let { processNoteId(noteId = it) }
                QrCodeDataType.NOTE -> result.value.bech32ToHexOrNull()?.let { processNoteId(noteId = it) }
                QrCodeDataType.NOTE_URI -> result.value.extractNoteId()?.let { processNoteId(noteId = it) }

                // A bolt11 invoice can be paid directly through the system's Lightning app. LNURL,
                // a bare lightning: uri and an on-chain bitcoin address/uri have no such fallback
                // without a wallet of our own.
                QrCodeDataType.LNBC -> setEffect(SideEffect.PayLightningInvoice(invoice = result.value))

                else -> Unit
            }
        }

    private fun processProfileId(profileId: String) {
        setEffect(SideEffect.NostrProfileDetected(profileId = profileId))
    }

    private fun processNoteId(noteId: String) {
        setEffect(SideEffect.NostrNoteDetected(noteId = noteId))
    }

    private fun processNaddr(naddr: String) {
        val naddrObject = naddr.takeAsNaddrOrNull()
        when (naddrObject?.kind) {
            NostrEventKind.LiveActivity.value -> {
                setEffect(SideEffect.NostrLiveStreamDetected(naddr = naddr))
            }
            NostrEventKind.LongFormContent.value -> {
                setEffect(SideEffect.NostrArticleDetected(naddr = naddr))
            }
            else -> {
                // Ignore unsupported naddr kinds
            }
        }
    }
}
