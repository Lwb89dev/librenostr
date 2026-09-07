package net.primal.android.settings.tor

import android.content.Context
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching

const val ORBOT_PACKAGE_NAME = "org.torproject.android"

fun isOrbotInstalled(context: Context): Boolean =
    runCatching {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE_NAME, 0) != null
    }.getOrDefault(false)
