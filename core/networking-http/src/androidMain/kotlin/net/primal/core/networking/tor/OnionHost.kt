package net.primal.core.networking.tor

private const val ONION_SUFFIX = ".onion"

/**
 * Whether this host name is a Tor onion service.
 *
 * Matches on the whole last label, not on a substring: `abc.onion` is an onion service,
 * `abc.onion.example.com` (a clearnet host that merely contains the word) and `onion` alone are not.
 * Anything ending in `.onion` counts, even a malformed name such as `.onion`: both places that ask
 * (route choice and DNS) treat "onion" as the safe answer, so a doubtful name is never sent out.
 * Case and a trailing root dot (`abc.onion.`, which DNS treats as the same name) are ignored, because
 * a check that a differently-cased or dotted spelling slips past would be a bypass.
 *
 * Deciding this by name is safe in both directions: a name ending in `.onion` cannot be resolved by
 * any resolver but Tor, so it is never legitimately a direct destination.
 */
fun String.isOnionHost(): Boolean {
    val host = trim().lowercase().removeSuffix(".")
    return host.endsWith(ONION_SUFFIX)
}
