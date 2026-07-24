package com.mattmooneyham.base.android.managers.logManager

/**
 * Boundary for crash and error reporting (Crashlytics, Sentry, ...).
 * The template ships only the seam plus a no-op default; wire a real
 * reporter through AppConfig.crashReporter when adopting one. Lives
 * beside its owning manager (the LogManager is the telemetry
 * funnel), like every other port.
 *
 * Production shapes, for copy-paste at the app edge (never in a
 * manager). Crashlytics (recordFatal stays Unit: the Crashlytics
 * handler is the PREVIOUS handler in the AppComponent's chain and
 * captures the fatal itself when we delegate):
 * ```
 * override fun recordNonFatal(throwable: Throwable) =
 *     crashlytics.recordException(throwable)
 * override fun recordFatal(throwable: Throwable) = Unit
 * override fun recordBreadcrumb(message: String) =
 *     crashlytics.log(message)
 * ```
 * Sentry:
 * ```
 * override fun recordNonFatal(throwable: Throwable) {
 *     Sentry.captureException(throwable)
 * }
 * override fun recordFatal(throwable: Throwable) {
 *     Sentry.captureException(throwable)
 * }
 * override fun recordBreadcrumb(message: String) {
 *     Sentry.addBreadcrumb(message)
 * }
 * ```
 */
interface CrashReporter {

    /**
     * Reports an error that was caught and handled but is still worth
     * counting in the crash backend. The LogManager calls this for
     * every accepted ERROR line (an ERROR line IS a non-fatal), so
     * app code normally just logs errors; call it directly only for
     * failures deliberately kept out of the log.
     */
    fun recordNonFatal(throwable: Throwable)

    /**
     * Reports a crash that is taking the process down. Called by the
     * uncaught-exception handler the AppComponent installs; must do its
     * work synchronously since the process dies right after.
     */
    fun recordFatal(throwable: Throwable)

    /**
     * Adds one line of crash-time context to the backend's bounded
     * breadcrumb ring (Crashlytics `log`, Sentry `addBreadcrumb`).
     * The LogManager forwards every event-bus trigger and every
     * accepted WARN/ERROR line here, so production crash reports
     * carry the recent app history. Called on hot paths:
     * implementations must be cheap and must never throw. Default is
     * a no-op so existing implementations keep compiling.
     */
    fun recordBreadcrumb(message: String) = Unit
}

/** Default reporter: crashes are logged locally but reported nowhere. */
object NoOpCrashReporter : CrashReporter {
    override fun recordNonFatal(throwable: Throwable) = Unit
    override fun recordFatal(throwable: Throwable) = Unit
}
