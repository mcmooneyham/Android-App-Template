package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.api.createHttpClient
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.ConnectivityMonitor
import com.mattmooneyham.base.android.managers.LogManager
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlinx.serialization.json.Json

/**
 * Everything the [AppComponent] needs from the outside world, gathered
 * into one value so the composition root has a single explicit input.
 * Every field except the files directory has a production default:
 * BaseApplication passes only what only it knows, and tests override
 * exactly the boundary they fake (for example [httpClientFactory] with
 * a Ktor MockEngine, or [connectivityMonitor] with a hand-driven
 * fake).
 *
 * @param appFilesDirectoryPath directory for app-private files (the
 *   app's filesDir); hosts the log file and the Preferences DataStore.
 * @param platformContext any Context, needed for network monitoring
 *   and the debug-build detection behind the default log level. Null
 *   (the JVM-test case) disables both.
 * @param minimumLogLevel lowest level that gets logged; null picks the
 *   platform default (DEBUG in debuggable builds, INFO in release, so
 *   trigger traces vanish from production).
 * @param apiBaseUrl base URL every ApiClient request resolves against.
 * @param httpClientFactory builds the shared HTTP client from the
 *   shared [Json]; the default picks the bundled OkHttp engine, tests
 *   substitute a MockEngine here.
 * @param connectivityMonitor the platform-connectivity boundary; null
 *   (the production default) builds the real Android monitor from
 *   [platformContext], tests inject a fake and drive it by hand.
 * @param clock source of wall time (log timestamps); tests pin it.
 * @param crashReporter crash backend seam (Crashlytics, Sentry, ...);
 *   the AppComponent's uncaught-exception handler reports fatals to
 *   it. Defaults to a no-op.
 * @param maxLogFileSizeBytes size at which the log file rotates to
 *   its single ".1" history file.
 */
data class AppConfig(
    val appFilesDirectoryPath: String,
    val platformContext: Any? = null,
    val minimumLogLevel: LogLevel? = null,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val httpClientFactory: (Json) -> HttpClient = ::createHttpClient,
    val connectivityMonitor: ConnectivityMonitor? = null,
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
