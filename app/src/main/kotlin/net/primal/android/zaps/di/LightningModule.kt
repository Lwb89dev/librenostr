package net.primal.android.zaps.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.primal.core.lightning.LightningAddressChecker
import net.primal.core.lightning.LightningPayHelper
import net.primal.core.utils.coroutines.DispatcherProvider

@Module
@InstallIn(SingletonComponent::class)
object LightningModule {

    @Provides
    @Singleton
    fun providesLightningAddressChecker(dispatcherProvider: DispatcherProvider): LightningAddressChecker =
        LightningAddressChecker(
            dispatcherProvider = dispatcherProvider,
        )

    @Provides
    @Singleton
    fun providesLightningPayHelper(dispatcherProvider: DispatcherProvider): LightningPayHelper =
        LightningPayHelper(dispatcherProvider = dispatcherProvider)
}
