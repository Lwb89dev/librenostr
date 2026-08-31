package net.primal.android.settings.language

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/** Supported LibreNostr translations. Names are shown in their native form. */
enum class AppLanguage(
    val tag: String,
    val nativeName: String,
) {
    SYSTEM_DEFAULT("", "System language"),
    ENGLISH("en", "English"),
    BULGARIAN("bg", "Български"),
    CROATIAN("hr", "Hrvatski"),
    CZECH("cs", "Čeština"),
    DANISH("da", "Dansk"),
    DUTCH("nl", "Nederlands"),
    ESTONIAN("et", "Eesti"),
    FINNISH("fi", "Suomi"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    GREEK("el", "Ελληνικά"),
    HUNGARIAN("hu", "Magyar"),
    IRISH("ga", "Gaeilge"),
    ITALIAN("it", "Italiano"),
    LATVIAN("lv", "Latviešu"),
    LITHUANIAN("lt", "Lietuvių"),
    MALTESE("mt", "Malti"),
    POLISH("pl", "Polski"),
    PORTUGUESE("pt", "Português"),
    ROMANIAN("ro", "Română"),
    SLOVAK("sk", "Slovenčina"),
    SLOVENIAN("sl", "Slovenščina"),
    SPANISH("es", "Español"),
    SWEDISH("sv", "Svenska"),
    CHINESE("zh", "简体中文"),
    JAPANESE("ja", "日本語"),
    RUSSIAN("ru", "Русский"),
    ;

    companion object {
        fun fromTag(tag: String): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM_DEFAULT
    }
}

/** Small, process-local preference used before Compose is created as well. */
object AppLanguageManager {
    private const val PREFS = "librenostr_preferences"
    private const val LANGUAGE_TAG = "language_tag"

    fun selectedLanguage(context: Context): AppLanguage = AppLanguage.fromTag(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGE_TAG, "")
            .orEmpty(),
    )

    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_TAG, language.tag)
            .apply()
    }

    fun wrap(context: Context): Context {
        val tag = selectedLanguage(context).tag
        if (tag.isBlank()) {
            val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale
            }
            Locale.setDefault(systemLocale)
            return context
        }

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }
        return ContextWrapper(context.createConfigurationContext(configuration))
    }
}
