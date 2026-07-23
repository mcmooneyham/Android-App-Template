package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.di.AppComponent
import com.mattmooneyham.base.android.di.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json

/**
 * Per-test harness around a REAL [AppComponent]. Everything inside the
 * component is production code; only the boundaries are faked:
 *
 * - HTTP: [FakeJokeApi] behind Ktor's MockEngine, injected through
 *   AppConfig.httpClientFactory (the component's construction-time
 *   joke fetch therefore never touches the network).
 * - Connectivity: [FakeConnectivityMonitor], driven by setConnected.
 * - Storage: a unique temporary directory per instance (DataStore
 *   allows one instance per file per process, so directories are
 *   never shared) hosting the real Preferences DataStore and log file.
 * - Wall time: [VirtualClock], pinned and settable.
 * - Main dispatcher: an [UnconfinedTestDispatcher] installed via
 *   Dispatchers.setMain BEFORE the component is built (the
 *   EventManager captures Main at construction). Unconfined delivery
 *   makes listener callbacks run synchronously with each trigger,
 *   while manager confinements remain real serial threads; awaiting
 *   assertions therefore use the real-time TestEventRecorder kit.
 *
 * Usage: construct fresh in each test (script [jokeApi] first when the
 * construction-time fetch matters) and ALWAYS [close] in teardown; it
 * closes the component, restores Main, and deletes the temp files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestAppContext(
    val jokeApi: FakeJokeApi = FakeJokeApi(),
    minimumLogLevel: LogLevel = LogLevel.DEBUG,
) {

    val mainDispatcher: TestDispatcher = UnconfinedTestDispatcher()
    val connectivityMonitor = FakeConnectivityMonitor()
    val clock = VirtualClock()

    /** Unique per-test files directory (DataStore, log file). */
    val filesDirectory: File =
        Files.createTempDirectory("test-app-files-").toFile()

    val component: AppComponent

    private var isClosed = false

    init {
        // Must precede component construction; see the class KDoc.
        Dispatchers.setMain(mainDispatcher)
        component = AppComponent(
            AppConfig(
                appFilesDirectory = filesDirectory,
                connectivityMonitor = connectivityMonitor,
                minimumLogLevel = minimumLogLevel,
                apiBaseUrl = TEST_API_BASE_URL,
                httpClientFactory = { json -> buildMockHttpClient(json) },
                clock = clock,
            ),
        )
    }

    /** A recorder on this component's event manager. */
    fun newRecorder(): TestEventRecorder =
        TestEventRecorder(component.eventManager)

    /**
     * Tears everything down: component first (its bus scope last, per
     * its own contract), then the Main override, then the temp files.
     * Safe to call twice, so specs may close eagerly AND in teardown.
     */
    fun close() {
        if (isClosed) return
        isClosed = true
        component.close()
        Dispatchers.resetMain()
        filesDirectory.deleteRecursively()
    }

    /**
     * Mirrors the production factory's behavior contract (expectSuccess
     * plus JSON content negotiation) on top of the scripted MockEngine.
     */
    private fun buildMockHttpClient(json: Json): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    with(jokeApi) { serveRequest(request) }
                }
            }
        }

    companion object {
        /** .invalid TLD: guaranteed unresolvable if wiring ever leaks. */
        const val TEST_API_BASE_URL = "https://joke.invalid/"
    }
}
