package net.primal.android.notes.translate

import io.github.aakira.napier.Napier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.primal.core.translation.BergamotTranslationEngine
import net.primal.core.utils.getOrElse
import net.primal.core.utils.runCatching

/**
 * Owns translate-note state and its coroutines on a scope of its own,
 * outliving any single note's composable. NoteContent.kt used to launch
 * translation from a per-note `rememberCoroutineScope()`; since a note item
 * is disposed when scrolled out of a LazyColumn's window, any translation or
 * pack download still in flight at that point got silently abandoned with
 * an uncaught `ForgottenCoroutineScopeException` — from the user's side, the
 * spinner just spun forever. Routing everything through this app-wide
 * singleton instead means the operation keeps running (and its result is
 * kept, ready to display again if the user scrolls back) regardless of what
 * the UI does meanwhile.
 */
@Singleton
class NoteTranslationCoordinator @Inject constructor(
    private val languagePackRepository: LanguagePackRepository,
    private val languageDetector: NoteLanguageDetector,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = ConcurrentHashMap<String, MutableStateFlow<NoteTranslationState>>()

    fun stateFor(noteId: String): StateFlow<NoteTranslationState> = stateFlowFor(noteId).asStateFlow()

    fun translate(noteId: String, content: String) {
        val state = stateFlowFor(noteId)
        state.value = NoteTranslationState.Loading
        scope.launch {
            state.value = runCatching { runTranslation(content) }
                .getOrElse {
                    Napier.w(throwable = it) { "Failed to translate note $noteId." }
                    NoteTranslationState.Error
                }
        }
    }

    fun confirmDownload(noteId: String, content: String, pack: LanguagePack) {
        val state = stateFlowFor(noteId)
        scope.launch {
            state.value = runCatching {
                languagePackRepository.install(pack) { downloaded, total ->
                    state.value = NoteTranslationState.Downloading(progress = downloaded.toFloat() / total.toFloat())
                }
                translateWithPack(pack, content)
            }.getOrElse {
                Napier.w(throwable = it) { "Failed to download/load language pack ${pack.directoryName}." }
                NoteTranslationState.Error
            }
        }
    }

    fun reset(noteId: String) {
        stateFlowFor(noteId).value = NoteTranslationState.Original
    }

    private fun stateFlowFor(noteId: String) =
        states.getOrPut(noteId) { MutableStateFlow(NoteTranslationState.Original) }

    private fun runTranslation(content: String): NoteTranslationState {
        val deviceLanguage = Locale.getDefault().language
        val detectStartMs = System.currentTimeMillis()
        val detectedLanguage = languageDetector.detectLanguageOf(content)
        val detectMs = System.currentTimeMillis() - detectStartMs
        val sourceIso = detectedLanguage.isoCode639_1.toString().lowercase()
        Napier.d { "Detected language for note: $detectedLanguage ($sourceIso), detect=${detectMs}ms." }
        val pack = languagePackRepository.findPack(source = sourceIso, target = deviceLanguage)

        return when {
            pack == null -> NoteTranslationState.NotAvailable
            !languagePackRepository.isInstalled(pack) -> NoteTranslationState.NeedsDownload(pack)
            else -> translateWithPack(pack, content)
        }
    }

    private fun translateWithPack(pack: LanguagePack, content: String): NoteTranslationState {
        val configPath = languagePackRepository.writeEngineConfig(pack)
        val loadStartMs = System.currentTimeMillis()
        val engine = BergamotTranslationEngine.create(configPath)
            ?: error("Failed to load language pack ${pack.directoryName}")
        val loadMs = System.currentTimeMillis() - loadStartMs
        return try {
            val translateStartMs = System.currentTimeMillis()
            val text = engine.translate(content)
            val translateMs = System.currentTimeMillis() - translateStartMs
            Napier.d { "Translated ${pack.directoryName} note: load=${loadMs}ms, translate=${translateMs}ms." }
            NoteTranslationState.Translated(text = text)
        } finally {
            engine.close()
        }
    }
}
