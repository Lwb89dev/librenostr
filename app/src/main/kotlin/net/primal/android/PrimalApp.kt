package net.primal.android

import android.app.Application
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.Napier
import javax.inject.Inject
import net.primal.android.core.crash.PrimalCrashReporter
import net.primal.android.core.images.PrimalImageLoaderFactory
import net.primal.core.config.store.AppConfigInitializer
import net.primal.data.account.repository.repository.factory.AccountRepositoryFactory
import net.primal.data.repository.factory.PrimalRepositoryFactory
import net.primal.wallet.data.repository.factory.WalletRepositoryFactory

@HiltAndroidApp
class PrimalApp : Application() {

    @Inject
    lateinit var antilog: Set<@JvmSuppressWildcards Antilog>

    @Inject
    lateinit var imageLoaderFactory: PrimalImageLoaderFactory

    @Inject
    lateinit var crashReporter: PrimalCrashReporter

    override fun onCreate() {
        super.onCreate()
        AppConfigInitializer.init(context = this@PrimalApp)
        PrimalRepositoryFactory.init(context = this@PrimalApp)
        WalletRepositoryFactory.init(
            context = this@PrimalApp,
            enableDbEncryption = !BuildConfig.DEBUG,
        )
        AccountRepositoryFactory.init(
            context = this@PrimalApp,
            enableDbEncryption = !BuildConfig.DEBUG,
        )

        SingletonImageLoader.setSafe(imageLoaderFactory)
        antilog.forEach { Napier.base(it) }

        if (BuildConfig.FEATURE_PRIMAL_CRASH_REPORTER) {
            crashReporter.init()
        }
    }
}
