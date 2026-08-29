package net.primal.android.security.di

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.primal.android.BuildConfig
import net.primal.android.security.AESEncryption
import net.primal.android.security.Encryption

@Module
@InstallIn(SingletonComponent::class)
object DebugSecurityModule {

    @Provides
    fun provideEncryption(): Encryption =
        AESEncryption(keyAlias = BuildConfig.LOCAL_STORAGE_KEY_ALIAS)

    @Provides
    fun provideDatabaseOpenHelper(): SupportSQLiteOpenHelper.Factory {
        return FrameworkSQLiteOpenHelperFactory()
    }
}
