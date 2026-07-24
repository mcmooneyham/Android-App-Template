package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.dataStoreManager.DATA_STORE_FILE_NAME
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.managers.dataStoreManager.createDataStoreScope
import com.mattmooneyham.base.android.managers.dataStoreManager.createPreferencesDataStore
import com.mattmooneyham.base.android.managers.eventManager.EventManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DataStoreManager against the REAL Preferences DataStore, backed by
 * the harness's per-test temporary directory: writes round-trip
 * through the actual store file and every value change reaches the
 * bus as a [HasSeenWelcomeChanged] event.
 */
class DataStoreManagerSpec {

    private var testContext: TestAppContext? = null

    private fun startApp(): TestAppContext =
        TestAppContext().also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `the welcome flag round-trips through the real store file`() =
        runBlocking<Unit> {
            val app = startApp()
            val dataStoreManager = app.component.dataStoreManager
            val recorder = app.newRecorder().record(HasSeenWelcomeChanged)

            // The stored default is published on startup.
            assertEquals(
                false,
                recorder.expectState(HasSeenWelcomeChanged),
            )

            // Write, then observe the change as an event AND read it
            // back through the store's own flow.
            dataStoreManager.setHasSeenWelcome(true)
            assertEquals(
                true,
                recorder.expectState(HasSeenWelcomeChanged),
            )
            assertEquals(true, dataStoreManager.hasSeenWelcome.first())

            // The value really persisted to the per-test store file.
            val storeFile =
                File(app.filesDirectory, DATA_STORE_FILE_NAME)
            assertTrue(storeFile.exists() && storeFile.length() > 0)

            // Clearing falls back to the default and is published too.
            dataStoreManager.clearHasSeenWelcome()
            assertEquals(
                false,
                recorder.expectState(HasSeenWelcomeChanged),
            )
            assertEquals(false, dataStoreManager.hasSeenWelcome.first())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the bus bridge survives a read failure and resubscribes`() =
        runBlocking<Unit> {
            // Direct construction with a fault-injected store: the
            // FIRST collection dies mid-read, and the bridge must
            // come back on its own (retryForever), publish the
            // stored default, and keep publishing writes.
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val storeDirectory =
                Files.createTempDirectory("flaky-store-").toFile()
            val storeScope = createDataStoreScope()
            val flakyStore = FlakyPreferencesDataStore(
                delegate = createPreferencesDataStore(
                    coroutineScope = storeScope,
                    produceFile = {
                        File(storeDirectory, DATA_STORE_FILE_NAME)
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
            val eventManager = EventManager()
            val dataStoreManager = DataStoreManager(
                dataStore = flakyStore,
                eventManager = eventManager,
                logManager = quietLogManager,
            )
            val recorder = TestEventRecorder(eventManager)
                .record(HasSeenWelcomeChanged)
            try {
                // The default arrives despite the first collection
                // failing: the resubscribe (500 ms backoff) worked.
                assertEquals(
                    false,
                    recorder.expectState(HasSeenWelcomeChanged),
                )
                assertTrue(flakyStore.collectionCount.get() >= 2)

                // The recovered bridge still publishes writes.
                dataStoreManager.setHasSeenWelcome(true)
                assertEquals(
                    true,
                    recorder.expectState(HasSeenWelcomeChanged),
                )

                // The outage was counted exactly once: first failure
                // logs at ERROR, which the funnel forwards.
                assertEquals(1, crashReporter.recordedNonFatals.size)
            } finally {
                dataStoreManager.close()
                eventManager.close()
                quietLogManager.close()
                storeScope.cancel()
                storeDirectory.deleteRecursively()
                Dispatchers.resetMain()
            }
        }
}
