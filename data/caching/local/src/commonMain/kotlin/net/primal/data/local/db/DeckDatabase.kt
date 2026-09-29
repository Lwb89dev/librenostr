package net.primal.data.local.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import net.primal.data.local.dao.decks.DeckColumnData
import net.primal.data.local.dao.decks.DeckDao
import net.primal.data.local.dao.decks.DeckData

/**
 * Deck/column layout is a local, per-device UI preference — not re-fetchable from anywhere,
 * unlike everything in [CachingDatabase]. It lives in its own database file specifically so it is
 * never touched by [CachingDatabase]'s wipe-on-oversized-cache or fallbackToDestructiveMigration
 * policies (see `AndroidRepositoryFactory`): both are fine for a pure cache, neither is fine for
 * data the user deliberately created.
 */
@Database(
    entities = [
        DeckData::class,
        DeckColumnData::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(DeckDatabaseConstructor::class)
abstract class DeckDatabase : RoomDatabase() {
    abstract fun decks(): DeckDao
}

@Suppress("KotlinNoActualForExpect", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantSuppression")
internal expect object DeckDatabaseConstructor : RoomDatabaseConstructor<DeckDatabase> {
    override fun initialize(): DeckDatabase
}
