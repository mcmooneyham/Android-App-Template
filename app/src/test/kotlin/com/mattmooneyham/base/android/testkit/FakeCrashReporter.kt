package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.di.CrashReporter

/**
 * In-memory crash backend for tests. Injected through
 * AppConfig.crashReporter (via [TestAppContext]) so specs can assert
 * exactly what the AppComponent's uncaught-exception handler reported.
 */
class FakeCrashReporter : CrashReporter {

    val recordedNonFatals = mutableListOf<Throwable>()
    val recordedFatals = mutableListOf<Throwable>()

    override fun recordNonFatal(throwable: Throwable) {
        recordedNonFatals += throwable
    }

    override fun recordFatal(throwable: Throwable) {
        recordedFatals += throwable
    }
}
