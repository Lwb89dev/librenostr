package net.primal.android.core.di

import android.content.ContentResolver
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.primal.android.core.crash.PrimalCrashReporter
import net.primal.android.messages.security.Nip04MessageCipher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.domain.nostr.cryptography.MessageCipher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver = context.contentResolver

    @Provides
    fun provideCrashReporter(okHttpClient: OkHttpClient) = PrimalCrashReporter(okHttpClient = okHttpClient)

    @Provides
    fun messagesCipher(nip04MessageCipher: Nip04MessageCipher): MessageCipher = nip04MessageCipher

    @Provides
    fun provideDispatcher(): DispatcherProvider = createDispatcherProvider()
}
