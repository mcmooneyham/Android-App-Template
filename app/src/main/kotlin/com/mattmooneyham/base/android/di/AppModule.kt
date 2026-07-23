package com.mattmooneyham.base.android.di

import android.content.Context
import com.mattmooneyham.base.android.BaseApplication
import com.mattmooneyham.base.android.api.ApiClient
import com.mattmooneyham.base.android.managers.DataStoreManager
import com.mattmooneyham.base.android.managers.EventManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.LogManager
import com.mattmooneyham.base.android.managers.NetworkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Thin Hilt adapter over the manual composition root: every provider
 * reads a member FROM the single [AppComponent] that [BaseApplication]
 * constructs in onCreate, before any injection resolves. No scoping is
 * needed; the component already holds single instances, and Hilt never
 * constructs a manager itself.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideAppComponent(
        @ApplicationContext applicationContext: Context,
    ): AppComponent =
        (applicationContext as BaseApplication).appComponent

    @Provides
    fun provideEventManager(component: AppComponent): EventManager =
        component.eventManager

    @Provides
    fun provideLogManager(component: AppComponent): LogManager =
        component.logManager

    @Provides
    fun provideNetworkManager(component: AppComponent): NetworkManager =
        component.networkManager

    @Provides
    fun provideDataStoreManager(
        component: AppComponent,
    ): DataStoreManager = component.dataStoreManager

    @Provides
    fun provideApiClient(component: AppComponent): ApiClient =
        component.apiClient

    @Provides
    fun provideJokeManager(component: AppComponent): JokeManager =
        component.jokeManager
}
