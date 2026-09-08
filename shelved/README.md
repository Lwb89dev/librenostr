# Shelved

Code that's been pulled out of the active build but is worth keeping around
rather than deleting outright.

## `translation-engine/` + `app-notes-translate/`

On-device note translation (Bergamot/Marian NMT, cross-compiled for Android
via NDK/CMake — see this session's history for the full native-build
investigation). Removed because almost nobody used it and the native build
added a large amount of time to every compile, but the engineering behind
it (a working arm64-v8a cross-compile of bergamot-translator + marian-dev +
sentencepiece + ssplit-cpp + ruy + pcre2, plus the JNI bridge and the
Kotlin-side language-pack manager) is real and reusable if this ever comes
back.

- `translation-engine/` was the Gradle module `:core:translation-engine`
  (native engine + JNI bridge).
- `app-notes-translate/` was `app/src/main/kotlin/net/primal/android/notes/translate/`
  (language detection, download coordinator, language-pack repository) plus
  `translation_language_packs.json` (was `app/src/main/assets/`).

**To bring it back:**
1. Move `translation-engine/` back to `core/translation-engine/`, and
   `app-notes-translate/`'s `.kt` files back to
   `app/src/main/kotlin/net/primal/android/notes/translate/` (the `.json`
   file back to `app/src/main/assets/`).
2. Re-add `include(":core:translation-engine")` to `settings.gradle.kts`.
3. Re-add to `app/build.gradle.kts`: `implementation(project(":core:translation-engine"))`,
   `implementation(libs.lingua)`, and the Lingua packaging-excludes block
   (check git history around the removal commit for the exact block).
4. Re-wire the call sites removed at the same time: `NoteContent.kt`'s
   translate button, `PrimalActivity.kt`'s `NoteTranslationCoordinator`
   injection/`LocalNoteTranslationCoordinator`, `ContentDisplaySettings`'s
   `translateNotesEnabled` field and its settings-screen toggle.
5. Re-add the `note_translate_*`/`settings_content_display_translate_notes*`
   string keys (check git history for the removal commit — they existed in
   all 26 locales).
6. `minSdk` was raised to 28 specifically for this feature (`iconv`/
   `iconv_open`, unavailable before Android 9) and was reverted to 26 in
   the same removal — raise it again if resurrecting this.
