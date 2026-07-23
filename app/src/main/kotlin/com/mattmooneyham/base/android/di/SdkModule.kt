package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.BaseSdk
import com.mattmooneyham.base.android.api.ApiClient
import com.mattmooneyham.base.android.managers.DataStoreManager
import com.mattmooneyham.base.android.managers.EventManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.LogManager
import com.mattmooneyham.base.android.managers.NetworkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Exposes the [BaseSdk] singletons to Hilt so app code can @Inject them.
 * No scoping is needed: BaseSdk already holds single instances, wired by
 * BaseSdk.initialize(...), which the app's Application class MUST call in
 * onCreate before any injection resolves these providers.
 */
@Module
@InstallIn(SingletonComponent::class)
object SdkModule {

    @Provides
    fun provideEventManager(): EventManager = BaseSdk.eventManager

    @Provides
    fun provideLogManager(): LogManager = BaseSdk.logManager

    @Provides
    fun provideNetworkManager(): NetworkManager = BaseSdk.networkManager

    @Provides
    fun provideDataStoreManager(): DataStoreManager = BaseSdk.dataStoreManager

    @Provides
    fun provideApiClient(): ApiClient = BaseSdk.apiClient

    @Provides
    fun provideJokeManager(): JokeManager = BaseSdk.jokeManager
}
