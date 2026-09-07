package net.primal.core.networking.tor

import kotlinx.serialization.Serializable

const val DEFAULT_ORBOT_SOCKS_PORT = 9050
const val MIN_SOCKS_PORT = 1
const val MAX_SOCKS_PORT = 65535

@Serializable
data class TorProxySettings(
    val enabled: Boolean = false,
    val socksPort: Int = DEFAULT_ORBOT_SOCKS_PORT,
)

fun Int.isValidSocksPort(): Boolean = this in MIN_SOCKS_PORT..MAX_SOCKS_PORT
