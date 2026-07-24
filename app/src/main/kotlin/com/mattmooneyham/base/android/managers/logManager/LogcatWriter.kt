package com.mattmooneyham.base.android.managers.logManager

import android.util.Log
import com.mattmooneyham.base.android.constants.LogLevel

/**
 * Logcat edge adapter: the one place log output touches the Android
 * framework. Kept out of [LogManager] so the manager itself stays free
 * of android.* imports, mirroring how [com.mattmooneyham.base.android.managers.connectivityManager.AndroidConnectivityMonitor]
 * isolates the connectivity hookup. Callers guard this with
 * runCatching: android.util.Log is a stub that throws in JVM unit
 * tests, and "logging never throws" includes the Logcat mirror.
 */
internal fun writeToLogcat(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?,
) {
    when (level) {
        LogLevel.DEBUG -> Log.d(tag, message, throwable)
        LogLevel.INFO -> Log.i(tag, message, throwable)
        LogLevel.WARN -> Log.w(tag, message, throwable)
        LogLevel.ERROR -> Log.e(tag, message, throwable)
    }
}
