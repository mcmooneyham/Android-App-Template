package com.mattmooneyham.base.android.di

/**
 * Boundary for crash and error reporting (Crashlytics, Sentry, ...).
 * The template ships only the seam plus a no-op default; wire a real
 * reporter through [AppConfig.crashReporter] when adopting one.
 */
interface CrashReporter {

    /**
     * Reports an error that was caught and handled but is still worth
     * counting in the crash backend (failed background work, contract
     * violations). Call it from app code; nothing calls it built-in.
     */
    fun recordNonFatal(throwable: Throwable)

    /**
     * Reports a crash that is taking the process down. Called by the
     * uncaught-exception handler the AppComponent installs; must do its
     * work synchronously since the process dies right after.
     */
    fun recordFatal(throwable: Throwable)
}

/** Default reporter: crashes are logged locally but reported nowhere. */
object NoOpCrashReporter : CrashReporter {
    override fun recordNonFatal(throwable: Throwable) = Unit
    override fun recordFatal(throwable: Throwable) = Unit
}
