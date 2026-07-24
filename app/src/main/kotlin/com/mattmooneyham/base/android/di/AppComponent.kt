package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.api.createDefaultJson
import com.mattmooneyham.base.android.managers.dataStoreManager.DATA_STORE_FILE_NAME
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.featureFlagManager.AppFlags
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkManager
import com.mattmooneyham.base.android.managers.dataStoreManager.createDataStoreScope
import com.mattmooneyham.base.android.managers.dataStoreManager.createPreferencesDataStore
import io.ktor.client.HttpClient
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

/**
 * The app's composition root: a plain class that wires every manager by
 * manual constructor injection. Constructing the component is what
 * starts the managers' event publishing; BaseApplication builds exactly
 * ONE instance per process, and Hilt's AppModule exposes its members to
 * @Inject sites. Tests build a fresh component per test (with boundary
 * fakes supplied through [AppConfig]) and [close] it in teardown.
 *
 * Wiring conventions:
 * - Properties initialize top to bottom, so declaration order IS the
 *   construction order: bus, logging, then peer managers.
 * - Managers receive only what they use, by constructor, within a
 *   budget of five parameters (a bus handle counts as one). Never
 *   inject the component itself into a manager.
 * - The one sanctioned setter cycle is [EventManager.attachLogManager],
 *   called in the init block below; add no others.
 */
class AppComponent(config: AppConfig) {

    private val constructionStart = TimeSource.Monotonic.markNow()

    val eventManager = EventManager()

    val logManager = LogManager(
        logDirectoryPath = config.appFilesDirectory.absolutePath,
        minimumLogLevel = config.minimumLogLevel,
        eventManager = eventManager,
        clock = config.clock,
        maxLogFileSizeBytes = config.maxLogFileSizeBytes,
    )

    init {
        // The one sanctioned cycle: the bus needs the logger for trigger
        // traces, the logger needs the bus to announce clears. From here
        // on, every trigger is traced and every payload is validated.
        eventManager.attachLogManager(logManager)
    }

    val networkManager = NetworkManager(
        logManager = logManager,
        eventManager = eventManager,
        // The boundary seam: BaseApplication passes the real Android
        // monitor, tests inject a fake. The component itself never
        // touches Android connectivity types.
        connectivityMonitor = config.connectivityMonitor,
    )

    // Held here, not inside the factory, so close() can cancel the
    // DataStore's IO work; the manager itself never sees this scope.
    private val dataStoreScope = createDataStoreScope()

    val dataStoreManager = DataStoreManager(
        dataStore = createPreferencesDataStore(
            coroutineScope = dataStoreScope,
            produceFile = {
                File(config.appFilesDirectory, DATA_STORE_FILE_NAME)
            },
        ),
        eventManager = eventManager,
        logManager = logManager,
    )

    // Debug-only flag-override storage: when disabled (release), the
    // scope and store are never created, so the build is structurally
    // locked to compiled defaults plus whatever a wired provider
    // supplies. Its own store file: DataStore allows one instance per
    // file, and the app's preference store belongs to DataStoreManager.
    private val flagOverridesScope =
        if (config.featureFlagOverridesEnabled) {
            createDataStoreScope()
        } else {
            null
        }

    val featureFlagManager = FeatureFlagManager(
        flags = AppFlags.all,
        overridesStore = flagOverridesScope?.let { overridesScope ->
            createPreferencesDataStore(
                coroutineScope = overridesScope,
                produceFile = {
                    File(
                        config.appFilesDirectory,
                        FeatureFlagManager.FLAG_OVERRIDES_FILE_NAME,
                    )
                },
            )
        },
        provider = config.featureFlagProvider,
        eventManager = eventManager,
        logManager = logManager,
    )

    val json: Json = createDefaultJson()

    // The ONE shared HTTP engine (connection pools, JSON negotiation,
    // and the httpClientFactory test seam). Managers wrap it in their
    // own per-endpoint ApiClients (see JokeManager for the pattern);
    // the component owns the engine so close() shuts it down once,
    // however many clients wrap it.
    val httpClient: HttpClient = config.httpClientFactory(json)

    val jokeManager = JokeManager(
        httpClient = httpClient,
        logManager = logManager,
        eventManager = eventManager,
        featureFlagManager = featureFlagManager,
    )

    // Crash safety: the handler below chains in FRONT of whatever was
    // installed (typically the platform's process-killing handler),
    // reports the fatal, gets the crash and any queued lines onto disk
    // synchronously, then delegates. Every step is guarded: a broken
    // reporter or logger must never mask the original crash.
    private val previousUncaughtExceptionHandler =
        Thread.getDefaultUncaughtExceptionHandler()

    private val crashUncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching { config.crashReporter.recordFatal(throwable) }
            runCatching {
                logManager.error(
                    "Uncaught exception on thread ${thread.name}",
                    throwable = throwable,
                )
                logManager.flushForCrash()
            }
            previousUncaughtExceptionHandler
                ?.uncaughtException(thread, throwable)
        }

    init {
        Thread.setDefaultUncaughtExceptionHandler(
            crashUncaughtExceptionHandler,
        )
        logManager.info(
            "AppComponent constructed in " +
                "${constructionStart.elapsedNow().inWholeMilliseconds} ms",
        )
    }

    /**
     * Tears the component down in REVERSE construction order. The
     * event-bus scope is cancelled LAST, so events triggered by earlier
     * teardown steps can still deliver. A closed component cannot be
     * reused; construct a new one (which tests do per test).
     */
    fun close() {
        // Uninstall the crash handler first so a crash during or after
        // teardown never routes through closed managers; restore only
        // if ours is still installed (never clobber a later one).
        if (Thread.getDefaultUncaughtExceptionHandler() ===
            crashUncaughtExceptionHandler
        ) {
            Thread.setDefaultUncaughtExceptionHandler(
                previousUncaughtExceptionHandler,
            )
        }
        jokeManager.close()
        httpClient.close()
        featureFlagManager.close()
        flagOverridesScope?.cancel()
        dataStoreManager.close()
        dataStoreScope.cancel()
        networkManager.close()
        logManager.close()
        eventManager.close()
    }
}
