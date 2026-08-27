package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.api.createHttpClient
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.connectivityManager.ConnectivityMonitor
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagProvider
import com.mattmooneyham.base.android.managers.featureFlagManager.NoOpFeatureFlagProvider
import com.mattmooneyham.base.android.managers.logManager.CrashReporter
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.logManager.NoOpCrashReporter
import com.mattmooneyham.base.android.managers.logManager.NoOpPlatformLogWriter
import com.mattmooneyham.base.android.managers.logManager.PlatformLogWriter
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
 *   filesDir); hosts the logs/ subdirectory and the Preferences
 *   DataStore. Tests pass a unique temporary directory per test.
 * @param appCacheDirectory directory for regenerable files (the app's
 *   cacheDir); hosts the log export zip, which the FileProvider grant
 *   in log_export_paths.xml serves from there. Tests pass a unique
 *   temporary directory per test.
 * @param connectivityMonitor the connectivity boundary; production
 *   passes an AndroidConnectivityMonitor built in BaseApplication,
 *   tests inject a fake and drive it by hand.
 * @param minimumLogLevel lowest level that gets logged. The default is
 *   INFO (safe for release); BaseApplication passes DEBUG in debug
 *   builds, so trigger traces vanish from the production log file
 *   and platform mirror (crash-report breadcrumbs still carry every
 *   trigger).
 * @param httpClientFactory builds the ONE shared HTTP client from the
 *   shared [Json]; the default picks the bundled OkHttp engine, tests
 *   substitute a MockEngine here. Endpoints are NOT configured here:
 *   each manager declares its own base URL beside itself and wraps
 *   this shared client in its own per-endpoint ApiClient (see
 *   JokeManager).
 * @param clock source of wall time (log timestamps); tests pin it.
 * @param crashReporter crash backend seam (Crashlytics, Sentry, ...);
 *   the AppComponent's uncaught-exception handler reports fatals to
 *   it. Defaults to a no-op.
 * @param maxLogFileSizeBytes size at which the day's log file rolls
 *   to its next numbered sibling.
 * @param maxLogRetentionDays days of log history kept, the current
 *   day included; older files are pruned in the background.
 * @param maxLogTotalSizeBytes retention cap across all log files;
 *   the oldest files are pruned first when it is exceeded.
 * @param platformLogWriter the platform log mirror; production passes
 *   the AndroidLogWriter (Logcat) adapter built in BaseApplication,
 *   JVM tests keep the no-op default.
 * @param featureFlagProvider remote flag backend seam; the no-op
 *   default supplies nothing, so compiled defaults (and debug
 *   overrides) decide every flag.
 * @param featureFlagOverridesEnabled whether local flag overrides are
 *   available and persisted. BaseApplication passes BuildConfig.DEBUG:
 *   release builds never create the override store, so they stay
 *   locked to default/provider values. Defaults to false so the lock
 *   is opt-out.
 */
data class AppConfig(
    val appFilesDirectory: File,
    val appCacheDirectory: File,
    val connectivityMonitor: ConnectivityMonitor,
    val minimumLogLevel: LogLevel = LogLevel.INFO,
    val httpClientFactory: (Json) -> HttpClient = ::createHttpClient,
    val clock: Clock = Clock.System,
    val crashReporter: CrashReporter = NoOpCrashReporter,
    val maxLogFileSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_FILE_BYTES,
    val maxLogRetentionDays: Int = LogManager.DEFAULT_MAX_LOG_DAYS,
    val maxLogTotalSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_TOTAL_BYTES,
    val platformLogWriter: PlatformLogWriter = NoOpPlatformLogWriter,
    val featureFlagProvider: FeatureFlagProvider = NoOpFeatureFlagProvider,
    val featureFlagOverridesEnabled: Boolean = false,
)
