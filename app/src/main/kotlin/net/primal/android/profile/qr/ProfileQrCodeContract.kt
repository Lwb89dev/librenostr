package net.primal.android.profile.qr

import net.primal.android.core.compose.profile.model.ProfileDetailsUi
import net.primal.android.scanner.domain.QrCodeResult

interface ProfileQrCodeContract {
    data class UiState(
        val profileId: String,
        val profileDetails: ProfileDetailsUi? = null,
    )

    sealed class UiEvent {
        data class ProcessQrCodeResult(val result: QrCodeResult) : UiEvent()
    }

    sealed class SideEffect {
        data class NostrProfileDetected(val profileId: String) : SideEffect()
        data class NostrNoteDetected(val noteId: String) : SideEffect()
        data class NostrArticleDetected(val naddr: String) : SideEffect()
        data class PayLightningInvoice(val invoice: String) : SideEffect()
    }

    data class ScreenCallbacks(
        val onClose: () -> Unit,
        val onProfileScan: (profileId: String) -> Unit,
        val onNoteScan: (noteId: String) -> Unit,
        val onArticleScan: (naddr: String) -> Unit,
        val onPayLightningInvoice: (invoice: String) -> Unit,
    )
}
