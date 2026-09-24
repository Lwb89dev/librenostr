package net.primal.android

import android.app.Application
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.Napier
import javax.inject.Inject
import net.primal.android.core.images.PrimalImageLoaderFactory
import net.primal.android.networking.relays.OutboxRelayCoordinator
import net.primal.core.config.store.AppConfigInitializer
import net.primal.android.core.tor.TorRuntimeBinder
import net.primal.core.networking.tor.TorProxyContextHolder
import net.primal.data.account.repository.repository.factory.AccountRepositoryFactory
import net.primal.data.repository.factory.PrimalRepositoryFactory

@HiltAndroidApp
class PrimalApp : Application() {

    @Inject
    lateinit var antilog: Set<@JvmSuppressWildcards Antilog>

    @Inject
    lateinit var imageLoaderFactory: PrimalImageLoaderFactory

    // dagger.Lazy, not a direct @Inject field: OutboxRelayCoordinator's constructor chain reaches
    // RelayRepository -> NostrPublisher -> CachingImportRepository -> AndroidRepositoryFactory's
    // caching database, and that database throws until PrimalRepositoryFactory.init() has run
    // (below). A direct field is constructed eagerly during Hilt's injection pass inside
    // super.onCreate() — before init() gets the chance to run — and crashed the app on launch.
    // Lazy defers actual construction to the explicit .get() call after init(), while still only
    // building it once, at process start, rather than whenever something else first touches it.
    @Inject
    lateinit var outboxRelayCoordinatorLazy: Lazy<OutboxRelayCoordinator>

    override fun onCreate() {
        // Must run before super.onCreate(): the Ktor OkHttp engine factory has no Hilt scope to
        // receive a Context from, and Hilt's field injection for this class (crashReporter,
        // imageLoaderFactory below) happens during super.onCreate() — this needs to be ready
        // before anything in that graph could construct an HttpClient.
        TorProxyContextHolder.seed(this)
        // Starts the built-in Tor engine when the saved settings ask for it, and watches the
        // foreground/background and network changes it has to react to. Also before super.onCreate():
        // the first connection any client makes may already need the engine's port.
        TorRuntimeBinder.bind(this)
        super.onCreate()
        AppConfigInitializer.init(context = this@PrimalApp)
        PrimalRepositoryFactory.init(context = this@PrimalApp)
        AccountRepositoryFactory.init(
            context = this@PrimalApp,
            enableDbEncryption = !BuildConfig.DEBUG,
        )

        SingletonImageLoader.setSafe(imageLoaderFactory)
        antilog.forEach { Napier.base(it) }
        // Triggers OutboxRelayCoordinator's construction (and its init{} enrichment loop) now
        // that PrimalRepositoryFactory.init() above has already run — see the field's own comment.
        outboxRelayCoordinatorLazy.get()
    }
}
