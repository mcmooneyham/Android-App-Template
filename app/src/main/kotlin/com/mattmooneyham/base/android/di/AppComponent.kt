package com.mattmooneyham.base.android.di

import com.mattmooneyham.base.android.api.createDefaultJson
import com.mattmooneyham.base.android.managers.dataStoreManager.DATA_STORE_FILE_NAME
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.featureFlagManager.AppFlags
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.jokeManager.JokeManager
import com.mattmooneyham.base.android.managers.logManager.LogFileSettings
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.logManager.LogSinks
import com.mattmooneyham.base.android.managers.connectivityManager.ConnectivityManager
import com.mattmooneyham.base.android.managers.dataStoreManager.createDataStoreScope
import com.mattmooneyham.base.android.managers.dataStoreManager.createPreferencesDataStore
import io.ktor.client.HttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
 * - Every member registers its own teardown BESIDE its declaration
 *   ([closedBy] for plain resources, [registered] for managers), so
 *   close-mirrors-construction is true by construction and [close] is
 *   never edited by feature PRs. The ONE hand-maintained exception is
 *   the crash-handler restore at the top of [close]: it must run
 *   before every teardown step and only when our handler is still
 *   installed, which the registry cannot express.
 * - Feature managers are APPENDED at the end of their marked region;
 *   they may depend on the infrastructure tier (bus, logging, flags,
 *   httpClient) but never on each other: cross-feature conversation
 *   rides published events. Two concurrent feature PRs therefore
 *   merge as trivially adjacent additions.
 * - Construction performs no network IO (the init budget in the
 *   ConfinedManager KDoc); first fetches run in [start], which
 *   BaseApplication calls right after construction.
 */
class AppComponent(config: AppConfig) {

    private val constructionStart = TimeSource.Monotonic.markNow()

    // Teardown steps in REVERSE construction order: each member
    // records its own step beside its declaration via closedBy or
    // registered, and addFirst keeps the list mirrored for free.
    // close() walks it; the only hand-ordered step is the
    // crash-handler restore, which precedes the walk (see close).
    private val teardownSteps = ArrayDeque<() -> Unit>()

    /** Registers the teardown for a non-manager resource (a scope,
     * the HTTP engine) beside its declaration. */
    private fun <ResourceType> ResourceType.closedBy(
        releaseResource: (ResourceType) -> Unit,
    ): ResourceType {
        teardownSteps.addFirst { releaseResource(this) }
        return this
    }

    // Managers in construction order, walked by start().
    private val managersInConstructionOrder =
        mutableListOf<ConfinedManager>()

    // Makes start() idempotent for CONCURRENT callers too, not just
    // repeated ones: compareAndSet admits exactly one winner, so
    // "managers see at most one start()" is a guarantee rather than
    // a caller convention.
    private val hasStarted = AtomicBoolean(false)

    /** Registers a manager's start order AND teardown at once. */
    private fun <ManagerType : ConfinedManager>
    ManagerType.registered(): ManagerType {
        managersInConstructionOrder += this
        val manager = this
        teardownSteps.addFirst { manager.close() }
        return this
    }

    // ---- Infrastructure tier (fixed; edit rarely) -----------------

    val eventManager = EventManager()
        .closedBy { bus -> bus.close() }

    val logManager = LogManager(
        fileSettings = LogFileSettings(
            directoryPath = config.appFilesDirectory.absolutePath,
            maxFileSizeBytes = config.maxLogFileSizeBytes,
        ),
        minimumLogLevel = config.minimumLogLevel,
        eventManager = eventManager,
        clock = config.clock,
        sinks = LogSinks(
            platformWriter = config.platformLogWriter,
            crashReporter = config.crashReporter,
        ),
    ).registered()

    init {
        // The one sanctioned cycle: the bus needs the logger for trigger
        // traces, the logger needs the bus to announce clears. From here
        // on, every trigger is traced and every payload is validated.
        eventManager.attachLogManager(logManager)
    }

    val connectivityManager = ConnectivityManager(
        logManager = logManager,
        eventManager = eventManager,
        // The boundary seam: BaseApplication passes the real Android
        // monitor, tests inject a fake. The component itself never
        // touches Android connectivity types.
        connectivityMonitor = config.connectivityMonitor,
    ).registered()

    // Held here, not inside the factory, so close() can cancel the
    // DataStore's IO work; the manager itself never sees this scope.
    private val dataStoreScope = createDataStoreScope()
        .closedBy { scope -> scope.cancel() }

    val dataStoreManager = DataStoreManager(
        dataStore = createPreferencesDataStore(
            coroutineScope = dataStoreScope,
            produceFile = {
                File(config.appFilesDirectory, DATA_STORE_FILE_NAME)
            },
        ),
        eventManager = eventManager,
        logManager = logManager,
    ).registered()

    // Debug-only flag-override storage: when disabled (release), the
    // scope and store are never created, so the build is structurally
    // locked to compiled defaults plus whatever a wired provider
    // supplies. Its own store file: DataStore allows one instance per
    // file, and the app's preference store belongs to DataStoreManager.
    private val flagOverridesScope =
        if (config.featureFlagOverridesEnabled) {
            createDataStoreScope().closedBy { scope -> scope.cancel() }
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
    ).registered()

    val json: Json = createDefaultJson()

    // The ONE shared HTTP engine (connection pools, JSON negotiation,
    // and the httpClientFactory test seam). Managers wrap it in their
    // own per-endpoint ApiClients (see JokeManager for the pattern);
    // the component owns the engine so close() shuts it down once,
    // however many clients wrap it.
    val httpClient: HttpClient = config.httpClientFactory(json)
        .closedBy { client -> client.close() }

    // ---- Feature managers (APPEND ONLY: one contiguous block per
    //      manager, added at the END of this region) -----------------

    val jokeManager = JokeManager(
        httpClient = httpClient,
        logManager = logManager,
        eventManager = eventManager,
        featureFlagManager = featureFlagManager,
    ).registered()

    // ---- Crash handler (fixed; stays last) -------------------------

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
                // logFatal, not error: recordFatal above already
                // counted this crash; the funnel must not count it
                // again as a non-fatal.
                logManager.logFatal(
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
     * Starts every manager in construction order, running the
     * post-construction side effects (first fetches, warmups) the
     * init budget keeps out of constructors. BaseApplication calls
     * this immediately after construction today; when a measured
     * cold start attributes real cost here, this single call is what
     * moves behind the first frame, and the lazy-tier plan in
     * ARCHITECTURE-SCALING.md tiers it further. Idempotent: repeated
     * calls are no-ops, so managers genuinely see at most one
     * start() per component however many callers reach it.
     */
    fun start() {
        if (!hasStarted.compareAndSet(false, true)) return
        managersInConstructionOrder.forEach { manager ->
            manager.start()
        }
    }

    /**
     * Tears the component down in REVERSE construction order by
     * walking the self-registered [teardownSteps]; the mirror is
     * structural (only the crash-handler restore below is
     * hand-ordered). The event-bus scope is
     * cancelled LAST, so events triggered by earlier teardown steps
     * can still deliver. A closed component cannot be reused;
     * construct a new one (which tests do per test).
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
        // Guarded per step: one throwing teardown must not strand the
        // members constructed before it. Each failure is reported,
        // never swallowed, and the walk continues.
        val teardownStepCount = teardownSteps.size
        teardownSteps.forEachIndexed { stepIndex, teardownStep ->
            runCatching { teardownStep() }.onFailure { stepFailure ->
                // stderr by design: the log manager may already be
                // closed mid-walk, so this is the last-resort channel.
                System.err.println(
                    "AppComponent close(): teardown step " +
                        "${stepIndex + 1} of $teardownStepCount failed",
                )
                stepFailure.printStackTrace(System.err)
            }
        }
    }
}
