package net.primal.core.networking.tor

import kotlinx.serialization.Serializable

/**
 * How the app's network traffic leaves the device.
 *
 * There are deliberately only three, and the difference between them is *which destinations* use
 * Tor, never *whether a destination silently falls back*:
 *
 * - [DIRECT]: ordinary networking. Tor stays off.
 * - [TOR]: every connection goes through Tor and there is no fallback. If Tor is starting, restarting
 *   or has failed, connections wait or fail; they never go out directly. This is the strict mode.
 * - [ONION_ONLY]: `.onion` destinations go through Tor (they are unreachable any other way), everything
 *   else is direct. It hides nothing about clearnet traffic and must be described that way.
 *
 * A "Tor unless it is inconvenient" mode is intentionally absent: without a policy that names exactly
 * which traffic is covered it would be a fail-open switch, which is the leak a Tor mode exists to
 * prevent.
 */
@Serializable
enum class NetworkMode { DIRECT, TOR, ONION_ONLY }
