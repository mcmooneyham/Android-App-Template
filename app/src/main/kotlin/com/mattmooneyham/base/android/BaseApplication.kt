package com.mattmooneyham.base.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. BaseSdk.initialize wires every SDK singleton
 * (and starts the event publishers) in common code; Hilt's SdkModule then
 * simply exposes those singletons to @Inject sites, so the app declares
 * only @HiltAndroidApp here and @AndroidEntryPoint on activities.
 */
@HiltAndroidApp
class BaseApplication : Application() {

    override fun onCreate() {
        BaseSdk.initialize(
            appFilesDirectoryPath = filesDir.path,
            platformContext = this,
        )
        BaseSdk.logManager.info(
            "Application created " +
                "(version ${BuildConfig.VERSION_NAME}, " +
                "build ${BuildConfig.BUILD_TIMESTAMP_SECONDS})",
        )
        super.onCreate()
    }

}
