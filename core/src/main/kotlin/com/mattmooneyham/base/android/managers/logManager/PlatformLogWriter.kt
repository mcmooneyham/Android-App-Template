package com.mattmooneyham.base.android.managers.logManager

import com.mattmooneyham.base.android.constants.LogLevel

/**
 * Port for mirroring log lines to the platform's native logger
 * (Logcat on Android: see AndroidLogWriter in :app's platform
 * package). Called on every accepted line, so implementations must
 * be cheap; the LogManager guards each call with runCatching anyway,
 * because "logging never throws" includes the mirror.
 */
fun interface PlatformLogWriter {
    fun write(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    )
}

/** Default writer: file and breadcrumbs only, no platform mirror
 * (exactly what JVM tests want). */
val NoOpPlatformLogWriter = PlatformLogWriter { _, _, _, _ -> }

/**
 * Where log output leaves the manager besides the file: the platform
 * mirror and the crash-backend seam, grouped into one value so the
 * constructor stays within the composition root's five-parameter
 * wiring budget (the same trick as [LogFileSettings]).
 */
data class LogSinks(
    val platformWriter: PlatformLogWriter = NoOpPlatformLogWriter,
    val crashReporter: CrashReporter = NoOpCrashReporter,
)
