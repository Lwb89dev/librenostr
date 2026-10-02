package net.primal.android.networking.relays

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.aakira.napier.Napier
import net.primal.core.networking.sockets.RelayConnectionBackoff

/**
 * Clears every relay's connection backoff whenever the device's connectivity meaningfully changes.
 *
 * [RelayConnectionBackoff] makes a relay that failed to connect sit out an exponentially growing
 * window (up to five minutes) instead of being re-dialled on every request. That is right for a
 * relay that is down, and wrong for a relay that only failed because the *phone* was offline: when
 * the network drops, every relay fails at once and they all start backing off together, and without
 * this the app would stay dark for however long that backoff had grown by the time the connection
 * came back. Two moments are treated as "the network changed, start over":
 * - a different default network (Wi-Fi to mobile data, a new Wi-Fi, a VPN coming up), or the same
 *   one coming back after it was lost;
 * - the current network regaining internet access (Android's `VALIDATED` capability) — the case of
 *   a Wi-Fi that stayed connected while its upstream went away, where no new network appears.
 *
 * Kept apart from `TorRuntimeBinder`, which watches the same callbacks for an unrelated reason
 * (rebuilding Tor circuits), so neither has to know about the other.
 */
object RelayBackoffNetworkBinder {

    fun bind(application: Application) {
        val manager = application.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            manager.registerDefaultNetworkCallback(BackoffResettingCallback())
        } catch (error: SecurityException) {
            Napier.w(error) { "Cannot watch network changes; relay backoff will only expire on its own" }
        }
    }

    private class BackoffResettingCallback : ConnectivityManager.NetworkCallback() {
        private var currentNetwork: Network? = null
        private var validated = false

        override fun onAvailable(network: Network) {
            if (network == currentNetwork) return
            currentNetwork = network
            validated = false
            forgive(reason = "default network changed")
        }

        override fun onLost(network: Network) {
            if (network != currentNetwork) return
            currentNetwork = null
            validated = false
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val nowValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (nowValidated && !validated) forgive(reason = "network regained internet access")
            validated = nowValidated
        }

        private fun forgive(reason: String) {
            Napier.i { "Relay connection backoff cleared: $reason" }
            RelayConnectionBackoff.Shared.resetAll()
        }
    }
}
