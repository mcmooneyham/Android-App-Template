package com.mattmooneyham.base.android.di

import android.content.Context
import com.mattmooneyham.base.android.BaseApplication
import com.mattmooneyham.base.android.BuildConfig
import com.mattmooneyham.base.android.constants.BuildInfo
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.jokeManager.JokeManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.connectivityManager.ConnectivityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Thin Hilt adapter over the manual composition root: every MANAGER
 * provider reads a member FROM the single [AppComponent] that
 * [BaseApplication] constructs in onCreate, before any injection
 * resolves (plain build metadata like BuildInfo is constructed in
 * place; it is not a component member). No scoping is
 * needed; the component already holds single instances, and Hilt never
 * constructs a manager itself.
 *
 * WHY HILT AT ALL: the app's own wiring is manual, and a hand-rolled
 * ViewModelProvider.Factory could replace this file. Hilt is kept for
 * the Android edges a growing app hits early, at the price of one
 * annotation per site: `by viewModels()` ergonomics today, and
 * SavedStateHandle, assisted injection, WorkManager/Service injection,
 * and @HiltAndroidTest tooling the day they are needed, with no
 * rewiring. Delete the hilt/ksp plugins plus this file and swap in a
 * manual factory if that trade reads the other way for your product.
 *
 * INJECTION IS AVAILABLE FROM Application.onCreate ONWARD, no
 * earlier: [AppComponentHost.appComponent] is assigned there.
 * ContentProvider-era consumers (androidx.startup initializers,
 * on-demand WorkManager configuration) initialize BEFORE onCreate and
 * would crash; none exist in the template, and adding one means
 * moving component construction into BaseApplication's constructor
 * or init block first.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideAppComponent(
        @ApplicationContext applicationContext: Context,
    ): AppComponent =
        // The HOST interface, not the concrete Application class:
        // under Hilt instrumentation tooling the application is
        // HiltTestApplication (or a @CustomTestApplication that can
        // implement the host), so a BaseApplication cast would throw.
        (applicationContext as AppComponentHost).appComponent

    @Provides
    fun provideEventManager(component: AppComponent): EventManager =
        component.eventManager

    @Provides
    fun provideLogManager(component: AppComponent): LogManager =
        component.logManager

    @Provides
    fun provideConnectivityManager(
        component: AppComponent,
    ): ConnectivityManager = component.connectivityManager

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
