package net.primal.core.networking.tor

import kotlinx.serialization.Serializable

const val DEFAULT_ORBOT_SOCKS_PORT = 9050
const val MIN_SOCKS_PORT = 1
const val MAX_SOCKS_PORT = 65535

/**
 * Which Tor client carries the traffic once Tor is [TorProxySettings.enabled].
 *
 * [ORBOT] is the original behavior and the default, so settings written before this option existed
 * (`enabled` and `socksPort` only) keep meaning exactly what they meant: an external SOCKS proxy on
 * `127.0.0.1:socksPort`. [BUILT_IN] runs Arti inside the app on a port it chooses itself.
 */
@Serializable
enum class TorEngineType { ORBOT, BUILT_IN }

@Serializable
data class TorProxySettings(
    /** Kept for settings written before [mode] existed, and mirrored by [withMode] for older readers. */
    val enabled: Boolean = false,
    /** Only used by [TorEngineType.ORBOT]; the built-in engine picks an ephemeral port. */
    val socksPort: Int = DEFAULT_ORBOT_SOCKS_PORT,
    val engine: TorEngineType = TorEngineType.ORBOT,
    /** Null on settings that predate the option; see [effectiveMode]. */
    val mode: NetworkMode? = null,
) {
    /**
     * The mode in force. Settings saved before [mode] existed only have [enabled], which always meant
     * "everything through Orbot, no fallback": exactly what [NetworkMode.TOR] is, so that is what they
     * map to and nothing changes for an existing install.
     */
    val effectiveMode: NetworkMode
        get() = mode ?: if (enabled) NetworkMode.TOR else NetworkMode.DIRECT

    /** Whether the in-app Tor client should run: some traffic goes through Tor and it is the chosen engine. */
    val wantsBuiltInEngine: Boolean
        get() = effectiveMode != NetworkMode.DIRECT && engine == TorEngineType.BUILT_IN

    /** The same settings with [newMode], keeping [enabled] consistent for anything that still reads it. */
    fun withMode(newMode: NetworkMode) = copy(mode = newMode, enabled = newMode != NetworkMode.DIRECT)
}

fun Int.isValidSocksPort(): Boolean = this in MIN_SOCKS_PORT..MAX_SOCKS_PORT
