package net.primal.core.utils

class AndroidAppBuildHelper : AppBuildHelper {

    override fun getAppVersion(): String = AndroidBuildConfig.APP_VERSION

    override fun getAppName(): String = "LibreNostr"

    override fun getPlatformName(): String = "Android"

    override fun getClientName(): String = "LibreNostr Android"
}

actual fun createAppBuildHelper(): AppBuildHelper = AndroidAppBuildHelper()
