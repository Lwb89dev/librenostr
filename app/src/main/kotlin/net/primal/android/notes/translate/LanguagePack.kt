package net.primal.android.notes.translate

import kotlinx.serialization.Serializable

/**
 * One downloadable asset that makes up a language pack: the model weights,
 * the lexical shortlist, or the shared vocabulary — a pack needs all three
 * to translate. Kept as a separate type (rather than three bare fields on
 * [LanguagePack]) because each one is downloaded, hashed, and installed
 * through the exact same code path in [LanguagePackRepository].
 */
@Serializable
data class LanguagePackAsset(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * A single (source, target) translation direction, e.g. "en" -> "it". Two
 * directions for the same language pair (en->it and it->en) are two separate
 * [LanguagePack] entries with independent install state, since a user only
 * ever needs the direction that ends at their own device language — the
 * reverse direction is a different, unrelated download.
 *
 * Parsed from `assets/translation_language_packs.json`, generated this
 * session from Mozilla's own Firefox Translations "tiny" models (falling
 * back to the "base" tier only for Chinese/Japanese, which have no tiny
 * variant at all) — see the translation-engine plan notes for exactly how
 * each entry's size/checksum was captured and why this specific source was
 * chosen over the alternatives that were tried and rejected.
 */
@Serializable
data class LanguagePack(
    val source: String,
    val target: String,
    val tier: String,
    val sizeBytes: Long,
    val model: LanguagePackAsset,
    val lex: LanguagePackAsset,
    val vocab: LanguagePackAsset,
) {
    val directoryName: String get() = "$source-$target"
}

@Serializable
internal data class LanguagePackManifest(
    val packs: List<LanguagePack>,
)
