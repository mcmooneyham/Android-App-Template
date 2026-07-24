package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagsChanged
import com.mattmooneyham.base.android.managers.featureFlagManager.NoOpFeatureFlagProvider
import com.mattmooneyham.base.android.managers.logManager.LogFileSettings
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.templateManager.TemplateEnrichmentFlag
import com.mattmooneyham.base.android.managers.templateManager.TemplateHistoryCleared
import com.mattmooneyham.base.android.managers.templateManager.TemplateManager
import com.mattmooneyham.base.android.managers.templateManager.TemplateStateChanged
import com.mattmooneyham.base.android.testkit.FakeFeatureFlagProvider
import com.mattmooneyham.base.android.testkit.FakeTemplatePort
import com.mattmooneyham.base.android.testkit.TestEventRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// <module>/src/test/kotlin/.../managers/, rename Template ->
// YourFeature, and delete these banners. This is the
// DIRECT-CONSTRUCTION spec shape: real manager, real bus, fakes at
// the boundaries, no mocking framework. Specs that need the WHOLE
// component (Hilt-adjacent wiring, cross-manager choreography through
// AppComponent) use TestAppContext instead and live in app/src/test.
// ===================================================================

/**
 * STEP 1: THE HARNESS. Main must be a test dispatcher BEFORE the bus
 * is built (the EventManager captures Main at construction);
 * unconfined delivery makes listener callbacks run synchronously with
 * each trigger, while the manager's confinement stays a real serial
 * thread, so assertions AWAIT events via TestEventRecorder rather
 * than assuming synchronous state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateManagerSpec {

    private lateinit var eventManager: EventManager
    private lateinit var logManager: LogManager
    private lateinit var flagManager: FeatureFlagManager
    private lateinit var templateManager: TemplateManager

    private val templatePort = FakeTemplatePort()
    private val flagProvider = FakeFeatureFlagProvider()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        eventManager = EventManager()
        // Quiet logger: file logging off, no assertions on it here.
        logManager = LogManager(
            fileSettings = LogFileSettings(
                directoryPath = null,
                fileLoggingEnabled = false,
            ),
        )
        flagManager = FeatureFlagManager(
            flags = listOf(TemplateEnrichmentFlag),
            overridesStore = null,
            provider = flagProvider,
            eventManager = eventManager,
            logManager = logManager,
        )
        templateManager = TemplateManager(
            port = templatePort,
            logManager = logManager,
            eventManager = eventManager,
            featureFlagManager = flagManager,
        )
    }

    // STEP 2: ALWAYS tear down in reverse: managers, then the bus,
    // then the Main override, or state leaks into the next test.
    @After
    fun tearDown() {
        templateManager.close()
        flagManager.close()
        logManager.close()
        eventManager.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `construction seeds the state and start warms it up`() =
        runBlocking<Unit> {
            val recorder = TestEventRecorder(eventManager)
                .record(TemplateStateChanged)

            // The init seed replays to the late subscriber.
            val seededState = recorder.expectState(TemplateStateChanged)
            assertEquals(0, seededState.readingCount)

            // Direct-construction specs call start() themselves
            // (TestAppContext's autoStart does it for component specs).
            templateManager.start()
            recorder.expectState(TemplateStateChanged)
            assertTrue(templatePort.isStarted)
        }

    @Test
    fun `port readings publish through the bus`() =
        runBlocking<Unit> {
            val recorder = TestEventRecorder(eventManager)
                .record(TemplateStateChanged)

            // The fake plays the platform's role; the callback hops
            // onto the confinement, so AWAIT the resulting event.
            templatePort.emitReading(42)

            val updatedState = recorder.expectStateMatching(
                TemplateStateChanged,
            ) { state -> state.latestReading == 42 }
            assertEquals(1, updatedState.readingCount)
        }

    @Test
    fun `clearHistory resets state and fires the signal`() =
        runBlocking<Unit> {
            val recorder = TestEventRecorder(eventManager)
                .record(TemplateStateChanged)
                .record(TemplateHistoryCleared)
            templatePort.emitReading(7)
            recorder.expectStateMatching(TemplateStateChanged) { state ->
                state.readingCount == 1
            }

            templateManager.clearHistory()

            recorder.expectStateMatching(TemplateStateChanged) { state ->
                state.readingCount == 0 && state.latestReading == null
            }
            // The signal fires AFTER the fact it announces is true.
            recorder.expectEvent(TemplateHistoryCleared)
            recorder.assertOrder(
                TemplateStateChanged,
                TemplateHistoryCleared,
            )
        }

    @Test
    fun `flags are read at decision time, not cached`() =
        runBlocking<Unit> {
            val recorder = TestEventRecorder(eventManager)
                .record(TemplateStateChanged)
                .record(FeatureFlagsChanged)

            // Flip the flag AFTER construction: the very next publish
            // must see it, proving nothing cached the answer.
            flagProvider.setFlags(
                mapOf(TemplateEnrichmentFlag.flagKey to true),
            )
            // DETERMINISM LESSON: the flag lands on the flag
            // manager's confinement and the reading on this manager's;
            // two serial dispatchers have NO mutual ordering, so a
            // spec must AWAIT the fact it just injected before acting
            // on it (the choreography spec's enableAutoRetry helper is
            // the same discipline). Without this await the test is a
            // coin flip.
            recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
                snapshot.flagsByKey[TemplateEnrichmentFlag.flagKey]
                    ?.enabled == true
            }

            templatePort.emitReading(1)

            recorder.expectStateMatching(TemplateStateChanged) { state ->
                state.latestReading == 1 && state.isEnriched
            }
        }
}
