package net.primal.android.gifpicker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.primal.data.remote.api.klipy.KlipyApi
import net.primal.data.remote.api.klipy.CommonsGifApiFactory

@Module
@InstallIn(SingletonComponent::class)
object KlipyApiModule {

    @Provides
    fun provideKlipyApi(): KlipyApi =
        CommonsGifApiFactory.create()
}
