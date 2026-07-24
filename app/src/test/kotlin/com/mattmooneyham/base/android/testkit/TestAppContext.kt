package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.api.createHttpClient
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.di.AppComponent
import com.mattmooneyham.base.android.di.AppConfig
import com.mattmooneyham.base.android.managers.logManager.CrashReporter
import com.mattmooneyham.base.android.managers.logManager.NoOpCrashReporter
import com.mattmooneyham.base.android.managers.logManager.LogManager
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Per-test harness around a REAL [AppComponent]. Everything inside the
 * component is production code; only the boundaries are faked:
 *
 * - HTTP: [FakeJokeApi] behind Ktor's MockEngine, injected through
 *   AppConfig.httpClientFactory (the startup joke fetch that start()
 *   runs therefore never touches the network).
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
 * Usage: construct fresh in each test (script [jokeApi] first when
 * the startup fetch matters: autoStart runs start() during harness
 * construction) and ALWAYS [close] in teardown; it
 * closes the component, restores Main, and deletes the temp files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestAppContext(
    val jokeApi: FakeJokeApi = FakeJokeApi(),
    minimumLogLevel: LogLevel = LogLevel.DEBUG,
    crashReporter: CrashReporter = NoOpCrashReporter,
    maxLogFileSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_FILE_BYTES,
    // Overrides default ON here (unlike production's release default):
    // specs exercise the debug behavior; pass false to spec the locked
    // release resolution.
    featureFlagOverridesEnabled: Boolean = true,
    // Mirrors BaseApplication, which calls start() right after
    // construction, so existing specs keep their semantics; pass
    // false to observe the construction window (the init budget).
    autoStart: Boolean = true,
    // The fake reports its initial state during start (the port
    // contract); pass one built with initialConnectivity = true to
    // spec a boot-while-online world.
    val connectivityMonitor: FakeConnectivityMonitor =
        FakeConnectivityMonitor(),
) {

    val mainDispatcher: TestDispatcher = UnconfinedTestDispatcher()
    val flagProvider = FakeFeatureFlagProvider()
    val clock = VirtualClock()

    /** Unique per-test files directory (DataStore, log file). */
    val filesDirectory: File =
        Files.createTempDirectory("test-app-files-").toFile()

    val component: AppComponent

    private var isClosed = false

    init {
        // Must precede component construction; see the class KDoc.
        Dispatchers.setMain(mainDispatcher)
        try {
            component = AppComponent(
                AppConfig(
                    appFilesDirectory = filesDirectory,
                    connectivityMonitor = connectivityMonitor,
                    minimumLogLevel = minimumLogLevel,
                    // The one HTTP seam: the PRODUCTION factory over a
                    // scripted MockEngine, so every spec exercises the
                    // real client configuration (expectSuccess,
                    // negotiation, timeouts) and ALL requests land on
                    // the fake whatever their endpoint URL.
                    httpClientFactory = { json ->
                        createHttpClient(
                            json = json,
                            engine = MockEngine { request ->
                                with(jokeApi) { serveRequest(request) }
                            },
                        )
                    },
                    clock = clock,
                    crashReporter = crashReporter,
                    maxLogFileSizeBytes = maxLogFileSizeBytes,
                    featureFlagProvider = flagProvider,
                    featureFlagOverridesEnabled =
                        featureFlagOverridesEnabled,
                ),
            )
        } catch (constructionFailure: Throwable) {
            // A failed construction must surface as ITSELF: close() is
            // unreachable on a half-built harness, so undo the Main
            // override and the temp directory here or they would leak
            // into every later test in the same JVM.
            Dispatchers.resetMain()
            filesDirectory.deleteRecursively()
            throw constructionFailure
        }
        if (autoStart) component.start()
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
        // Quiesce the log writer first: close() cancels scopes without
        // joining, so an unflushed append could otherwise recreate the
        // log file between the deletes below.
        runBlocking { component.logManager.flush() }
        component.close()
        Dispatchers.resetMain()
        deleteFilesDirectory()
    }

    /**
     * Deletes the temp directory, retrying briefly instead of ignoring
     * the result: one already-claimed log append may still be finishing
     * on the IO pool and can momentarily recreate the log file.
     */
    private fun deleteFilesDirectory() {
        repeat(DELETE_RETRY_ATTEMPTS) {
            if (filesDirectory.deleteRecursively()) return
            Thread.sleep(DELETE_RETRY_DELAY_MILLISECONDS)
        }
        check(filesDirectory.deleteRecursively()) {
            "Could not delete test files directory $filesDirectory"
        }
    }

    companion object {
        // Teardown deletion retry: ample for one in-flight file write.
        private const val DELETE_RETRY_ATTEMPTS = 10
        private const val DELETE_RETRY_DELAY_MILLISECONDS = 20L
    }
}
