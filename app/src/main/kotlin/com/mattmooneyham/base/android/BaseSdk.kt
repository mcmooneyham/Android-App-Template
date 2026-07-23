package com.mattmooneyham.base.android

import com.mattmooneyham.base.android.api.ApiClient
import com.mattmooneyham.base.android.api.createDefaultJson
import com.mattmooneyham.base.android.api.createHttpClient
import com.mattmooneyham.base.android.managers.DATA_STORE_FILE_NAME
import com.mattmooneyham.base.android.managers.DataStoreManager
import com.mattmooneyham.base.android.managers.EventManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.LogManager
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.NetworkManager
import com.mattmooneyham.base.android.managers.createPreferencesDataStore
import com.mattmooneyham.base.android.managers.defaultMinimumLogLevel
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json

/**
 * The app's singleton container: every manager is wired here and held
 * in lateinit vars. [initialize] must run once at app startup, BEFORE
 * anything reads these fields: BaseApplication.onCreate calls it with
 * the app's files directory and its Context; the Hilt SdkModule then
 * simply exposes these fields to @Inject sites.
 *
 * Initialization also guarantees the event-driven contract: constructing
 * the managers here is what starts their event publishing.
 */
object BaseSdk {

    // The Official Joke API powers the template's demo request (see
    // JokeManager). Replace with the real service's base URL when
    // building on the template.
    const val API_BASE_URL = "https://official-joke-api.appspot.com/"

    lateinit var eventManager: EventManager
        private set
    lateinit var logManager: LogManager
        private set
    lateinit var networkManager: NetworkManager
        private set
    lateinit var dataStoreManager: DataStoreManager
        private set
    lateinit var json: Json
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var jokeManager: JokeManager
        private set

    val isInitialized: Boolean
        get() = ::eventManager.isInitialized

    /**
     * Wires every SDK singleton. Safe to call more than once; only the
     * first call takes effect.
     *
     * @param appFilesDirectoryPath directory for app-private files
     *   (the app's filesDir); hosts the log file and the Preferences
     *   DataStore.
     * @param platformContext any Context, needed for network monitoring
     *   and the debug-build detection behind the default log level.
     * @param minimumLogLevel lowest level that gets logged; null picks
     *   the platform default (DEBUG in debuggable builds, INFO in
     *   release, so trigger traces vanish from production).
     */
    fun initialize(
        appFilesDirectoryPath: String,
        platformContext: Any? = null,
        minimumLogLevel: LogLevel? = null,
    ) {
        if (isInitialized) return
        val initializationStart = TimeSource.Monotonic.markNow()

        eventManager = EventManager()
        logManager = LogManager(
            logDirectoryPath = appFilesDirectoryPath,
            minimumLogLevel = minimumLogLevel
                ?: defaultMinimumLogLevel(platformContext),
            eventManager = eventManager,
        )
        // From here on, every trigger is traced and every payload is
        // validated against the Event contract.
        eventManager.attachLogManager(logManager)
        networkManager = NetworkManager(
            logManager = logManager,
            eventManager = eventManager,
            platformContext = platformContext,
        )
        dataStoreManager = DataStoreManager(
            dataStore = createPreferencesDataStore(
                producePath = {
                    "$appFilesDirectoryPath/$DATA_STORE_FILE_NAME"
                },
            ),
            eventManager = eventManager,
            logManager = logManager,
        )
        json = createDefaultJson()
        apiClient = ApiClient(createHttpClient(json), API_BASE_URL)
        jokeManager = JokeManager(
            apiClient = apiClient,
            logManager = logManager,
            eventManager = eventManager,
        )

        logManager.info(
            "BaseSdk initialized in " +
                "${initializationStart.elapsedNow().inWholeMilliseconds} ms",
        )
    }
}
