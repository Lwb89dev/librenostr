package net.primal.android.signer.client.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import net.primal.android.signer.client.AMBER_PACKAGE_NAME
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching

/* Amber 3.0.4 */
private const val COMPATIBLE_AMBER_VERSION_CODE = 115

fun isCompatibleAmberVersionInstalled(context: Context): Boolean {
    if (isExternalSignerInstalled(context)) return true
    return runCatching {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(AMBER_PACKAGE_NAME, 0)
        val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        installedVersionCode >= COMPATIBLE_AMBER_VERSION_CODE
    }.getOrDefault(false)
}

fun isExternalSignerInstalled(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
    val resolved = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved.isNotEmpty()
}
