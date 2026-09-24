package net.primal.android.core.tor

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import io.github.aakira.napier.Napier
import net.primal.core.networking.tor.engine.BuiltInTor

/**
 * Connects the built-in Tor engine to the two things about the device it has to react to, without the
 * engine knowing anything about Android's lifecycle or connectivity APIs.
 *
 * - **Foreground / background**: with no activity started the app is in the background, and the
 *   client is told so it can suspend its background work (it wakes on the next connection).
 * - **Network changes**: circuits built on one network are dead on the next. A different default
 *   network, or the same one coming back after being lost, asks the supervisor for a rebuild; the
 *   supervisor rate-limits it, because networks flap.
 *
 * Everything here is a no-op while the engine does not exist, so it can be registered unconditionally.
 */
object TorRuntimeBinder {

    fun bind(application: Application) {
        BuiltInTor.initialize(application)
        trackForeground(application)
        trackNetwork(application)
    }

    private fun trackForeground(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 0

                override fun onActivityStarted(activity: Activity) {
                    if (started++ == 0) BuiltInTor.setBackgrounded(false)
                }

                override fun onActivityStopped(activity: Activity) {
                    if (--started == 0) BuiltInTor.setBackgrounded(true)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun trackNetwork(application: Application) {
        val manager = application.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    private var lastNetwork: Network? = null
                    private var lost = false

                    override fun onAvailable(network: Network) {
                        val changed = lost || (lastNetwork != null && lastNetwork != network)
                        lastNetwork = network
                        lost = false
                        if (changed) BuiltInTor.onNetworkChanged()
                    }

                    override fun onLost(network: Network) {
                        if (network == lastNetwork) lost = true
                    }
                },
            )
        } catch (error: SecurityException) {
            Napier.w(error) { "Cannot watch network changes; built-in Tor will not rebuild on them" }
        }
    }
}
