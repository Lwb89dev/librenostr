package net.primal.android.signer.bunker

import java.net.URLDecoder
import net.primal.core.utils.ensureWsOrWss

/**
 * A parsed `bunker://` connection string (NIP-46): who to reach, on which relays, and how to
 * prove this is expected.
 */
data class BunkerUrl(
    val pubkey: String,
    val relays: List<String>,
    val secret: String?,
)

fun String.isBunkerUrl(): Boolean = startsWith(prefix = BUNKER_URL_PREFIX, ignoreCase = true)

fun String.parseBunkerUrlOrThrow(): BunkerUrl {
    require(isBunkerUrl()) { "Not a bunker connection: it must start with `$BUNKER_URL_PREFIX`." }

    val withoutScheme = removePrefix(BUNKER_URL_PREFIX)
    val queryIndex = withoutScheme.indexOf('?')
    val pubkey = if (queryIndex == -1) withoutScheme else withoutScheme.substring(0, queryIndex)
    require(pubkey.length == HEX_PUBKEY_LENGTH && pubkey.all { it.isHexDigit() }) {
        "Invalid bunker connection: the remote signer's pubkey is malformed."
    }

    val params = if (queryIndex == -1) emptyList() else withoutScheme.substring(queryIndex + 1).parseQueryParams()

    val relays = params
        .filter { (key, _) -> key == RELAY_PARAM }
        .map { (_, value) -> value.trimEnd('/') }
        .filter { it.isNotBlank() }
        .map { it.ensureWsOrWss() }
    require(relays.isNotEmpty()) { "Invalid bunker connection: no `$RELAY_PARAM` was provided." }

    return BunkerUrl(
        pubkey = pubkey,
        relays = relays,
        secret = params.lastOrNull { (key, _) -> key == SECRET_PARAM }?.second,
    )
}

private fun String.parseQueryParams(): List<Pair<String, String>> =
    split('&')
        .filter { it.isNotBlank() }
        .map { param ->
            val parts = param.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val BUNKER_URL_PREFIX = "bunker://"
private const val RELAY_PARAM = "relay"
private const val SECRET_PARAM = "secret"
private const val HEX_PUBKEY_LENGTH = 64
