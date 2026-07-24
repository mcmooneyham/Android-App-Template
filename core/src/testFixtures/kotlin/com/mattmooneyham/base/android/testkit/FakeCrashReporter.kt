package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.logManager.CrashReporter

/**
 * In-memory crash backend for tests. Injected through
 * AppConfig.crashReporter (via the app-test TestAppContext harness)
 * so specs can assert
 * exactly what the AppComponent's uncaught-exception handler reported.
 */
class FakeCrashReporter : CrashReporter {

    val recordedNonFatals = mutableListOf<Throwable>()
    val recordedFatals = mutableListOf<Throwable>()
    val recordedBreadcrumbs = mutableListOf<String>()

    override fun recordNonFatal(throwable: Throwable) {
        recordedNonFatals += throwable
    }

    override fun recordFatal(throwable: Throwable) {
        recordedFatals += throwable
    }

    override fun recordBreadcrumb(message: String) {
        recordedBreadcrumbs += message
    }
}
