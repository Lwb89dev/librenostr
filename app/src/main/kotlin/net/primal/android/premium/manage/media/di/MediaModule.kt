package net.primal.android.premium.manage.media.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.primal.android.premium.manage.media.api.MediaManagementApi
import net.primal.android.premium.manage.media.api.MediaManagementApiImpl

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    fun provideMediaApi(): MediaManagementApi = MediaManagementApiImpl()
}
