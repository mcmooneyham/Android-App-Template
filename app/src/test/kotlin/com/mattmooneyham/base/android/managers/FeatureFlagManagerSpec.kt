package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.dataStoreManager.createDataStoreScope
import com.mattmooneyham.base.android.managers.dataStoreManager.createPreferencesDataStore
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.featureFlagManager.AppFlags
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagsChanged
import com.mattmooneyham.base.android.managers.featureFlagManager.FlagSource
import com.mattmooneyham.base.android.managers.featureFlagManager.NoOpFeatureFlagProvider
import com.mattmooneyham.base.android.managers.featureFlagManager.ResolvedFlag
import com.mattmooneyham.base.android.managers.logManager.LogFileSettings
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.logManager.LogSinks
import com.mattmooneyham.base.android.testkit.FakeCrashReporter
import com.mattmooneyham.base.android.testkit.FlakyPreferencesDataStore
import com.mattmooneyham.base.android.testkit.TestAppContext
import com.mattmooneyham.base.android.testkit.TestEventRecorder
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The demo flag's stable identity, asserted against snapshots.
private val FLAG_KEY = JokeAutoRetryOnReconnectFlag.flagKey

/**
 * FeatureFlagManager resolution, against a REAL AppComponent: layer
 * precedence (override > provider > default), live re-resolution and
 * publication, debug-override persistence across manager rebuilds,
 * and the release lock (no override store means no override layer).
 */
class FeatureFlagManagerSpec {

    private var testContext: TestAppContext? = null

    private fun startApp(
        overridesEnabled: Boolean = true,
    ): TestAppContext = TestAppContext(
        featureFlagOverridesEnabled = overridesEnabled,
    ).also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `compiled defaults are resolved and published at startup`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(FeatureFlagsChanged)

            val snapshot = recorder.expectState(FeatureFlagsChanged)
            assertEquals(
                ResolvedFlag(enabled = false, source = FlagSource.DEFAULT),
                snapshot.flagsByKey[FLAG_KEY],
            )
            assertFalse(
                app.component.featureFlagManager
                    .isEnabled(JokeAutoRetryOnReconnectFlag),
            )
        }

    @Test
    fun `a provider update re-resolves and publishes`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(FeatureFlagsChanged)

            app.flagProvider.setFlags(mapOf(FLAG_KEY to true))

            recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
                snapshot.flagsByKey[FLAG_KEY] ==
                    ResolvedFlag(true, FlagSource.PROVIDER)
            }
            assertTrue(
                app.component.featureFlagManager
                    .isEnabled(JokeAutoRetryOnReconnectFlag),
            )
        }

    @Test
    fun `an override beats the provider and clearing restores it`() =
        runBlocking<Unit> {
            val app = startApp()
            val flagManager = app.component.featureFlagManager
            val recorder = app.newRecorder().record(FeatureFlagsChanged)

            app.flagProvider.setFlags(mapOf(FLAG_KEY to true))
            recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
                snapshot.flagsByKey[FLAG_KEY]?.source ==
                    FlagSource.PROVIDER
            }

            flagManager.setOverride(JokeAutoRetryOnReconnectFlag, false)
            recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
                snapshot.flagsByKey[FLAG_KEY] ==
                    ResolvedFlag(false, FlagSource.OVERRIDE)
            }
            assertFalse(
                flagManager.isEnabled(JokeAutoRetryOnReconnectFlag),
            )

            // Clearing the override exposes the provider layer again.
            flagManager.setOverride(JokeAutoRetryOnReconnectFlag, null)
            recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
                snapshot.flagsByKey[FLAG_KEY] ==
                    ResolvedFlag(true, FlagSource.PROVIDER)
            }
        }

    @Test
    fun `without an override store, setOverride is a locked no-op`() =
        runBlocking<Unit> {
            val app = startApp(overridesEnabled = false)
            val flagManager = app.component.featureFlagManager
            val recorder = app.newRecorder().record(FeatureFlagsChanged)
            recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
                snapshot.flagsByKey[FLAG_KEY]?.source ==
                    FlagSource.DEFAULT
            }

            flagManager.setOverride(JokeAutoRetryOnReconnectFlag, true)

            // Locked builds resolve defaults, publish nothing new, and
            // keep answering with the compiled value.
            recorder.assertNoEvent(FeatureFlagsChanged)
            assertFalse(
                flagManager.isEnabled(JokeAutoRetryOnReconnectFlag),
            )
        }

    @Test
    fun `overrides persist across manager rebuilds on the same store`() =
        runBlocking<Unit> {
            // Direct manager construction: TestAppContext deletes its
            // directory on close, so relaunch persistence is specified
            // on one shared store instance (DataStore allows a single
            // instance per file per process) across two managers.
            @OptIn(ExperimentalCoroutinesApi::class)
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val storeDirectory =
                Files.createTempDirectory("flag-store-").toFile()
            val storeScope = createDataStoreScope()
            val overridesStore = createPreferencesDataStore(
                coroutineScope = storeScope,
                produceFile = {
                    File(
                        storeDirectory,
                        FeatureFlagManager.FLAG_OVERRIDES_FILE_NAME,
                    )
                },
            )
            // File logging off: this spec needs no log assertions.
            val quietLogManager = LogManager(
                fileSettings = LogFileSettings(
                    directoryPath = null,
                    fileLoggingEnabled = false,
                ),
            )
            try {
                // "First launch": set an override and let it persist.
                val firstBus = EventManager()
                val firstManager = FeatureFlagManager(
                    flags = AppFlags.all,
                    overridesStore = overridesStore,
                    provider = NoOpFeatureFlagProvider,
                    eventManager = firstBus,
                    logManager = quietLogManager,
                )
                val firstRecorder = TestEventRecorder(firstBus)
                    .record(FeatureFlagsChanged)
                firstManager.setOverride(
                    JokeAutoRetryOnReconnectFlag,
                    true,
                )
                firstRecorder.expectStateMatching(
                    FeatureFlagsChanged,
                ) { snapshot ->
                    snapshot.flagsByKey[FLAG_KEY] ==
                        ResolvedFlag(true, FlagSource.OVERRIDE)
                }
                firstManager.close()
                firstBus.close()

                // "Relaunch": a fresh manager on the same store loads
                // the persisted override.
                val secondBus = EventManager()
                val secondManager = FeatureFlagManager(
                    flags = AppFlags.all,
                    overridesStore = overridesStore,
                    provider = NoOpFeatureFlagProvider,
                    eventManager = secondBus,
                    logManager = quietLogManager,
                )
                val secondRecorder = TestEventRecorder(secondBus)
                    .record(FeatureFlagsChanged)
                secondRecorder.expectStateMatching(
                    FeatureFlagsChanged,
                ) { snapshot ->
                    snapshot.flagsByKey[FLAG_KEY] ==
                        ResolvedFlag(true, FlagSource.OVERRIDE)
                }
                assertTrue(
                    secondManager
                        .isEnabled(JokeAutoRetryOnReconnectFlag),
                )
                secondManager.close()
                secondBus.close()
            } finally {
                quietLogManager.close()
                storeScope.cancel()
                storeDirectory.deleteRecursively()
                @OptIn(ExperimentalCoroutinesApi::class)
                Dispatchers.resetMain()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the override bridge survives a read failure and resubscribes`() =
        runBlocking<Unit> {
            // The same dead-bridge hazard as the DataStoreManager,
            // fixed the same way: the first store collection dies,
            // retryForever resubscribes, and overrides still apply.
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val storeDirectory =
                Files.createTempDirectory("flaky-flag-store-").toFile()
            val storeScope = createDataStoreScope()
            val flakyStore = FlakyPreferencesDataStore(
                delegate = createPreferencesDataStore(
                    coroutineScope = storeScope,
                    produceFile = {
                        File(
                            storeDirectory,
                            FeatureFlagManager.FLAG_OVERRIDES_FILE_NAME,
                        )
                    },
                ),
                failuresToServe = 1,
            )
            val crashReporter = FakeCrashReporter()
            val quietLogManager = LogManager(
                fileSettings = LogFileSettings(
                    directoryPath = null,
                    fileLoggingEnabled = false,
                ),
                sinks = LogSinks(crashReporter = crashReporter),
            )
            val eventBus = EventManager()
            val flagManager = FeatureFlagManager(
                flags = AppFlags.all,
                overridesStore = flakyStore,
                provider = NoOpFeatureFlagProvider,
                eventManager = eventBus,
                logManager = quietLogManager,
            )
            val recorder = TestEventRecorder(eventBus)
                .record(FeatureFlagsChanged)
            try {
                // An override set while the read side is recovering
                // still lands: writes pass through, and the
                // resubscribed collection picks them up.
                flagManager.setOverride(
                    JokeAutoRetryOnReconnectFlag,
                    true,
                )
                recorder.expectStateMatching(
                    FeatureFlagsChanged,
                ) { snapshot ->
                    snapshot.flagsByKey[FLAG_KEY] ==
                        ResolvedFlag(true, FlagSource.OVERRIDE)
                }
                assertTrue(flakyStore.collectionCount.get() >= 2)
                assertEquals(1, crashReporter.recordedNonFatals.size)
            } finally {
                flagManager.close()
                eventBus.close()
                quietLogManager.close()
                storeScope.cancel()
                storeDirectory.deleteRecursively()
                Dispatchers.resetMain()
            }
        }
}
