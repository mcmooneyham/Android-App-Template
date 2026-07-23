package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.api.createHttpClient
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.ConnectivityMonitor
import com.mattmooneyham.base.android.managers.LogManager
import io.ktor.client.HttpClient
import java.io.File
import kotlin.time.Clock
import kotlinx.serialization.json.Json

/**
 * Everything the [AppComponent] needs from the outside world, gathered
 * into one value so the composition root has a single explicit input.
 * Anything only the Android edge can supply (the files directory, the
 * connectivity hookup) arrives as a typed value or boundary built in
 * BaseApplication, so the component and managers never touch a Context
 * and JVM unit tests construct a real component from fakes alone.
 * Every other field has a production default: tests override exactly
 * the boundary they fake (for example [httpClientFactory] with a Ktor
 * MockEngine).
 *
 * @param appFilesDirectory directory for app-private files (the app's
 *   filesDir); hosts the log file and the Preferences DataStore. Tests
 *   pass a unique temporary directory per test.
 * @param connectivityMonitor the connectivity boundary; production
 *   passes an AndroidConnectivityMonitor built in BaseApplication,
 *   tests inject a fake and drive it by hand.
 * @param minimumLogLevel lowest level that gets logged. The default is
 *   INFO (safe for release); BaseApplication passes DEBUG in debug
 *   builds, so trigger traces vanish from production.
 * @param apiBaseUrl base URL every ApiClient request resolves against.
 * @param httpClientFactory builds the shared HTTP client from the
 *   shared [Json]; the default picks the bundled OkHttp engine, tests
 *   substitute a MockEngine here.
 * @param clock source of wall time (log timestamps); tests pin it.
 * @param crashReporter crash backend seam (Crashlytics, Sentry, ...);
 *   the AppComponent's uncaught-exception handler reports fatals to
 *   it. Defaults to a no-op.
 * @param maxLogFileSizeBytes size at which the log file rotates to
 *   its single ".1" history file.
 */
data class AppConfig(
    val appFilesDirectory: File,
    val connectivityMonitor: ConnectivityMonitor,
    val minimumLogLevel: LogLevel = LogLevel.INFO,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val httpClientFactory: (Json) -> HttpClient = ::createHttpClient,
    val clock: Clock = Clock.System,
    val crashReporter: CrashReporter = NoOpCrashReporter,
    val maxLogFileSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_FILE_BYTES,
) {

    companion object {
        // The Official Joke API powers the template's demo request (see
        // JokeManager). Replace with the real service's base URL when
        // building on the template.
        const val DEFAULT_API_BASE_URL =
            "https://official-joke-api.appspot.com/"
    }
}
