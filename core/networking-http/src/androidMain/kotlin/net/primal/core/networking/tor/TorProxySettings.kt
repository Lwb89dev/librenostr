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
    val enabled: Boolean = false,
    /** Only used by [TorEngineType.ORBOT]; the built-in engine picks an ephemeral port. */
    val socksPort: Int = DEFAULT_ORBOT_SOCKS_PORT,
    val engine: TorEngineType = TorEngineType.ORBOT,
)

fun Int.isValidSocksPort(): Boolean = this in MIN_SOCKS_PORT..MAX_SOCKS_PORT
