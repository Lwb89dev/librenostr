package net.primal.android.core.ext

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toUri
import net.primal.core.utils.runCatching

private val ALLOWED_URI_SCHEMES = setOf(
    "https",
    "http",
    "mailto",
    "lightning",
    "bitcoin",
    "nostr",
)

fun isSafeExternalUri(uri: String): Boolean {
    val parsed = runCatching { uri.toUri() }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase()
    if (scheme.isNullOrBlank()) return looksLikeHttpsHost(uri)
    if (scheme !in ALLOWED_URI_SCHEMES) return false
    if (scheme == "http" || scheme == "https") {
        val host = parsed.host ?: return false
        if (host.isBlank() || host.contains(" ")) return false
    }
    return true
}

private fun looksLikeHttpsHost(uri: String): Boolean {
    if (uri.contains(" ") || uri.contains("://")) return false
    return uri.contains('.')
}

fun UriHandler.openUriSafely(uri: String) {
    val sanitized = when {
        isSafeExternalUri(uri) -> uri
        looksLikeHttpsHost(uri) -> "https://$uri"
        else -> return
    }
    if (!isSafeExternalUri(sanitized)) return
    runCatching { openUri(sanitized) }
}

fun Context.openUriInExternalBrowser(uri: String) {
    if (!isSafeExternalUri(uri)) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val browserIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        intent.selector = browserIntent
        startActivity(intent)
    }
}
