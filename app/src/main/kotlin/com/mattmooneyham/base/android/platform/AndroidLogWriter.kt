package com.mattmooneyham.base.android.platform

import android.util.Log
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.logManager.PlatformLogWriter

/**
 * Logcat adapter for the [PlatformLogWriter] port: the one place log
 * output touches the Android framework. Built in BaseApplication and
 * wired through AppConfig.platformLogWriter; JVM tests keep the port's
 * no-op default, so nothing off-device ever touches android.util.Log.
 */
class AndroidLogWriter : PlatformLogWriter {

    override fun write(
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
}
