package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.api.ApiClient
import com.mattmooneyham.base.android.api.createDefaultJson
import com.mattmooneyham.base.android.managers.DATA_STORE_FILE_NAME
import com.mattmooneyham.base.android.managers.DataStoreManager
import com.mattmooneyham.base.android.managers.EventManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.LogManager
import com.mattmooneyham.base.android.managers.NetworkManager
import com.mattmooneyham.base.android.managers.createDataStoreScope
import com.mattmooneyham.base.android.managers.createPreferencesDataStore
import com.mattmooneyham.base.android.managers.defaultMinimumLogLevel
import kotlin.time.TimeSource
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

/**
 * The app's composition root: a plain class that wires every manager by
 * manual constructor injection. Constructing the component is what
 * starts the managers' event publishing; BaseApplication builds exactly
 * ONE instance per process, and Hilt's SdkModule exposes its members to
 * @Inject sites. Tests build a fresh component per test (with boundary
 * fakes supplied through [SdkConfig]) and [close] it in teardown.
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
class AppComponent(config: SdkConfig) {

    private val constructionStart = TimeSource.Monotonic.markNow()

    val eventManager = EventManager()

    val logManager = LogManager(
        logDirectoryPath = config.appFilesDirectoryPath,
        minimumLogLevel = config.minimumLogLevel
            ?: defaultMinimumLogLevel(config.platformContext),
        eventManager = eventManager,
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
        platformContext = config.platformContext,
    )

    // Held here, not inside the factory, so close() can cancel the
    // DataStore's IO work; the manager itself never sees this scope.
    private val dataStoreScope = createDataStoreScope()

    val dataStoreManager = DataStoreManager(
        dataStore = createPreferencesDataStore(
            coroutineScope = dataStoreScope,
            producePath = {
                "${config.appFilesDirectoryPath}/$DATA_STORE_FILE_NAME"
            },
        ),
        eventManager = eventManager,
        logManager = logManager,
    )

    val json: Json = createDefaultJson()

    val apiClient = ApiClient(
        httpClient = config.httpClientFactory(json),
        baseUrl = config.apiBaseUrl,
    )

    val jokeManager = JokeManager(
        apiClient = apiClient,
        logManager = logManager,
        eventManager = eventManager,
    )

    init {
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
        jokeManager.close()
        apiClient.httpClient.close()
        dataStoreManager.close()
        dataStoreScope.cancel()
        networkManager.stopMonitoring()
        logManager.close()
        eventManager.close()
    }
}
