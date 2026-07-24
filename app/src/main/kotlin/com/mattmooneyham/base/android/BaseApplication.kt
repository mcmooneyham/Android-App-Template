package com.mattmooneyham.base.android

import android.app.Application
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.di.AppComponent
import com.mattmooneyham.base.android.di.AppComponentHost
import com.mattmooneyham.base.android.di.AppConfig
import com.mattmooneyham.base.android.platform.AndroidConnectivityMonitor
import com.mattmooneyham.base.android.platform.AndroidLogWriter
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Constructs the process's single
 * [AppComponent] (which wires every manager and starts their event
 * publishing) before anything can inject; Hilt's AppModule then simply
 * exposes the component's members to @Inject sites, so the app declares
 * only @HiltAndroidApp here and @AndroidEntryPoint on activities.
 */
@HiltAndroidApp
class BaseApplication : Application(), AppComponentHost {

    /**
     * The composition root, alive for the whole process. Never closed
     * here: it dies with the process. AppModule reads it through the
     * [AppComponentHost] contract; instrumented tests reach the real
     * managers through this property.
     */
    override lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        // This is the Android edge: everything the component needs from
        // the platform is built HERE as a typed value or boundary, so
        // the component and managers stay Context-free.
        appComponent = AppComponent(
            AppConfig(
                appFilesDirectory = filesDir,
                connectivityMonitor = AndroidConnectivityMonitor(this),
                platformLogWriter = AndroidLogWriter(),
                // Debug builds log everything; release keeps INFO and
                // above, so trigger traces vanish from the release log
                // file and Logcat (crash-report breadcrumbs still
                // carry every trigger).
                minimumLogLevel = if (BuildConfig.DEBUG) {
                    LogLevel.DEBUG
                } else {
                    LogLevel.INFO
                },
                // Debug builds persist local flag overrides (Settings >
                // Debug); release builds never create the override
                // store, locking flags to their compiled defaults.
                featureFlagOverridesEnabled = BuildConfig.DEBUG,
            ),
        )
        appComponent.logManager.info(
            "Application created " +
                "(version ${BuildConfig.VERSION_NAME}, " +
                "build ${BuildConfig.BUILD_TIMESTAMP_SECONDS})",
        )
        // Construction wired everything without IO; start() runs the
        // managers' first fetches and warmups (the init budget).
        appComponent.start()
        super.onCreate()
    }

}
