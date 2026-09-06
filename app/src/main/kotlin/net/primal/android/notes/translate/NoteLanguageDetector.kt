package net.primal.android.notes.translate

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetector
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Lingua's LanguageDetector as an app-wide singleton — building one
 * per note, which is what NoteContent.kt used to do via a per-composable
 * `remember` block, exhausted the app's heap (OutOfMemoryError) after
 * scrolling through even a modest feed. One instance for the app's lifetime
 * instead.
 *
 * Two Lingua options matter here, both measured on-device (a real
 * translate tap otherwise blocked for ~50 seconds on language detection
 * alone):
 * - By default Lingua loads each language's n-gram frequency model lazily,
 *   the first time it's actually needed inside `detectLanguageOf` — so
 *   merely constructing the `LanguageDetector` (what `warmUp` used to force)
 *   does *not* preload anything. [withPreloadedLanguageModels] makes that
 *   loading happen eagerly instead, during construction, so [warmUp] moves
 *   the cost to app startup as intended rather than the user's first tap.
 * - Lingua's default mode loads n-gram models up to order 5, which is far
 *   more precision than distinguishing between our ~24 supported languages
 *   for note-length text needs. [withLowAccuracyMode] loads only unigram/
 *   bigram data, cutting both load time and memory substantially; the
 *   accuracy tradeoff mainly affects very closely related language pairs,
 *   which matters little here.
 */
@Singleton
class NoteLanguageDetector @Inject constructor(
    languagePackRepository: LanguagePackRepository,
) {
    // Lingua's builder only exposes a vararg overload for an arbitrary language set (no
    // List-accepting alternative), so the spread here is unavoidable — and harmless anyway,
    // since this only runs once per app lifetime, not on any hot path.
    @Suppress("SpreadOperator")
    private val detector: LanguageDetector by lazy {
        val supportedIsoCodes = languagePackRepository.availablePacks()
            .flatMap { listOf(it.source, it.target) }
            .toSet()
        val supportedLanguages = Language.entries.filter { it.isoCode639_1.toString().lowercase() in supportedIsoCodes }
        LanguageDetectorBuilder.fromLanguages(*supportedLanguages.toTypedArray())
            .withLowAccuracyMode()
            .withPreloadedLanguageModels()
            .build()
    }

    fun detectLanguageOf(text: String): Language = detector.detectLanguageOf(text)

    /**
     * Forces the lazy Lingua construction (and, per [withPreloadedLanguageModels], its model
     * loading) eagerly. Call this once from a background dispatcher at app startup so it isn't
     * the user's first translate tap that pays for it.
     */
    fun warmUp() {
        detector.let { }
    }
}
