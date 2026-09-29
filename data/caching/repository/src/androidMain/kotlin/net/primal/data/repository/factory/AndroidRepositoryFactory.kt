package net.primal.data.repository.factory

import android.content.Context
import net.primal.core.config.store.AppConfigInitializer
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.db.DeckDatabase
import net.primal.shared.data.local.db.LocalDatabaseFactory
import net.primal.shared.data.local.db.LocalDatabasePragmaConfig
import net.primal.shared.data.local.encryption.AndroidPlatformKeyStore

typealias PrimalRepositoryFactory = AndroidRepositoryFactory

object AndroidRepositoryFactory : CommonRepositoryFactory() {

    private var appContext: Context? = null

    private val cachingDatabase: CachingDatabase by lazy {
        val appContext = appContext ?: error("You need to call init(ApplicationContext) first.")
        LocalDatabaseFactory.deleteDatabases(
            context = appContext,
            names = CachingDatabase.OBSOLETE_FILE_NAMES,
        )
        LocalDatabaseFactory.deleteDatabaseIfOversized(
            context = appContext,
            databaseName = "caching_database.db",
            maxSizeBytes = CachingDatabase.MAX_DATABASE_SIZE_BYTES,
        )
        LocalDatabaseFactory.createDatabase<CachingDatabase>(
            context = appContext,
            fallbackToDestructiveMigration = true,
            databaseName = "caching_database.db",
            pragmaConfig = LocalDatabasePragmaConfig.CACHING,
        )
    }

    // No oversized-wipe, no fallbackToDestructiveMigration: unlike caching_database.db, this
    // holds data the user deliberately created and nothing can refetch — see DeckDatabase's own
    // doc for why it is a separate file rather than a table in the cache database.
    private val deckDatabase: DeckDatabase by lazy {
        val appContext = appContext ?: error("You need to call init(ApplicationContext) first.")
        LocalDatabaseFactory.createDatabase<DeckDatabase>(
            context = appContext,
            fallbackToDestructiveMigration = false,
            databaseName = "deck_database.db",
        )
    }

    fun init(context: Context) {
        this.appContext = context.applicationContext
        AndroidPlatformKeyStore.init(context)
        AppConfigInitializer.init(context)
    }

    override fun resolveCachingDatabase(): CachingDatabase = cachingDatabase

    override fun resolveDeckDatabase(): DeckDatabase = deckDatabase
}
