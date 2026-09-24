package net.primal.android.networking.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton
import net.primal.core.networking.tor.NetworkRoute
import net.primal.core.networking.tor.RouteController
import net.primal.core.networking.tor.applyNetworkRoute
import net.primal.core.utils.serialization.CommonJson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkingModule {

    @Provides
    @ElementsIntoSet
    fun emptyInterceptorsSet(): Set<Interceptor> = emptySet()

    private fun OkHttpClient.Builder.withInterceptors(interceptors: Collection<Interceptor>) =
        apply {
            interceptors.forEach { addInterceptor(it) }
        }

    /** The process-wide network route, so classes that hold long-lived connections can react to a mode switch. */
    @Provides
    @Singleton
    fun routeController(): RouteController = NetworkRoute.controller

    @Provides
    @Singleton
    fun unauthenticatedOkHttpClient(
        interceptors: Set<@JvmSuppressWildcards Interceptor>,
    ) = OkHttpClient.Builder()
        .withInterceptors(interceptors)
        .applyNetworkRoute()
        .build()

    @Provides
    @Singleton
    fun unauthenticatedRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://nostrich.org/")
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(CommonJson.asConverterFactory("application/json".toMediaType()))
            .build()
}
