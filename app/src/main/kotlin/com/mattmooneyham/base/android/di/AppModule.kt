package com.mattmooneyham.base.android.di

import android.content.Context
import com.mattmooneyham.base.android.BaseApplication
import com.mattmooneyham.base.android.BuildConfig
import com.mattmooneyham.base.android.constants.BuildInfo
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkManager
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
    fun provideFeatureFlagManager(
        component: AppComponent,
    ): FeatureFlagManager = component.featureFlagManager

    // App build metadata for the :ui module, which owns no application
    // BuildConfig of its own; plain data, not a component member.
    @Provides
    fun provideBuildInfo(): BuildInfo = BuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        buildTimestampSeconds = BuildConfig.BUILD_TIMESTAMP_SECONDS,
        isDebugBuild = BuildConfig.DEBUG,
    )

    // No ApiClient provider: clients are per-endpoint values owned by
    // their managers (see JokeManager), not shared graph members.

    @Provides
    fun provideJokeManager(component: AppComponent): JokeManager =
        component.jokeManager
}
