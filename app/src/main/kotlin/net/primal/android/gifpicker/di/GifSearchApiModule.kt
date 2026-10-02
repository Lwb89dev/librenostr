package net.primal.android.gifpicker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.primal.data.remote.api.gifs.GifSearchApi
import net.primal.data.remote.api.gifs.GifSearchApiFactory

@Module
@InstallIn(SingletonComponent::class)
object GifSearchApiModule {

    // A singleton so the provider fallback's memory (nostr.build refused us, use GIFverse for a
    // while) is shared by every picker instead of being relearned, one failed request each time.
    @Provides
    @Singleton
    fun provideGifSearchApi(): GifSearchApi = GifSearchApiFactory.create()
}
