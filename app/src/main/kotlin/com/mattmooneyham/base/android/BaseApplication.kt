package com.mattmooneyham.base.android

import android.app.Application
import com.mattmooneyham.base.android.di.AppComponent
import com.mattmooneyham.base.android.di.SdkConfig
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Constructs the process's single
 * [AppComponent] (which wires every manager and starts their event
 * publishing) before anything can inject; Hilt's SdkModule then simply
 * exposes the component's members to @Inject sites, so the app declares
 * only @HiltAndroidApp here and @AndroidEntryPoint on activities.
 */
@HiltAndroidApp
class BaseApplication : Application() {

    /**
     * The composition root, alive for the whole process. Never closed
     * here: it dies with the process. Instrumented tests reach the real
     * managers through this property.
     */
    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        appComponent = AppComponent(
            SdkConfig(
                appFilesDirectoryPath = filesDir.path,
                platformContext = this,
            ),
        )
        appComponent.logManager.info(
            "Application created " +
                "(version ${BuildConfig.VERSION_NAME}, " +
                "build ${BuildConfig.BUILD_TIMESTAMP_SECONDS})",
        )
        super.onCreate()
    }

}
