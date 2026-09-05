package net.primal.android.notes.translate

interface TranslateApi {

    /**
     * Translates [text] into [targetLanguage] (an ISO 639-1 code, e.g. "it") using the
     * LibreTranslate-compatible server at [serverUrl]. Source language is auto-detected.
     */
    suspend fun translate(serverUrl: String, text: String, targetLanguage: String): String
}
