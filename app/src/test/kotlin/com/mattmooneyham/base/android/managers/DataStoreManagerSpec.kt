package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.dataStoreManager.DATA_STORE_FILE_NAME
import com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.testkit.TestAppContext
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DataStoreManager against the REAL Preferences DataStore, backed by
 * the harness's per-test temporary directory: writes round-trip
 * through the actual store file and every value change reaches the
 * bus as a [com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged] event.
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
}
