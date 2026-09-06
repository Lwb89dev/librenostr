package net.primal.android.notes.translate

sealed class NoteTranslationState {
    data object Original : NoteTranslationState()
    data object Loading : NoteTranslationState()
    data class Translated(val text: String) : NoteTranslationState()
    data object Error : NoteTranslationState()

    // The detected source language has no shipped pack in either direction with the device's
    // language — e.g. Irish, which Mozilla's own model catalog has no pack for at all (checked
    // this session; not a gap specific to this app). Distinct from [Error] so the message tells
    // the user this genuinely isn't supported, not that something failed worth retrying.
    data object NotAvailable : NoteTranslationState()

    // A pack exists for the detected pair but isn't installed yet. Rendered as a confirmation
    // dialog (real size, on-device/offline disclosure) rather than a silent auto-download, per
    // the explicit requirement that downloads are opt-in per pack, not implicit.
    data class NeedsDownload(val pack: LanguagePack) : NoteTranslationState()

    data class Downloading(val progress: Float) : NoteTranslationState()
}
