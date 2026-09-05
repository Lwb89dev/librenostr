package net.primal.android.notes.translate

import kotlinx.serialization.Serializable

@Serializable
internal data class TranslateRequest(
    val q: String,
    val source: String = "auto",
    val target: String,
    val format: String = "text",
)

@Serializable
internal data class TranslateResponse(
    val translatedText: String,
)
