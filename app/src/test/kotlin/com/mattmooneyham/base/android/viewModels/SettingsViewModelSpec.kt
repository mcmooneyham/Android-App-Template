package com.mattmooneyham.base.android.viewModels

import com.mattmooneyham.base.android.BuildConfig
import com.mattmooneyham.base.android.managers.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.managers.LogsCleared
import com.mattmooneyham.base.android.testkit.TestAppContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsViewModel against the REAL component (fakes at the
 * boundaries only): its maintenance actions run through the real
 * managers and surface as events and real file changes.
 * viewModelScope rides the harness's Main override.
 */
class SettingsViewModelSpec {

    private var testContext: TestAppContext? = null

    private fun startApp(): TestAppContext =
        TestAppContext().also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    private fun buildViewModel(app: TestAppContext) = SettingsViewModel(
        dataStoreManager = app.component.dataStoreManager,
        logManager = app.component.logManager,
    )

    @Test
    fun `about values come straight from the build config`() {
        val app = startApp()
        val viewModel = buildViewModel(app)

        assertEquals(BuildConfig.VERSION_NAME, viewModel.appVersionName)
        assertEquals(
            BuildConfig.BUILD_TIMESTAMP_SECONDS,
            viewModel.buildTimestampSeconds,
        )
    }

    @Test
    fun `clearWelcomeFlag clears the flag through the real manager`() =
        runBlocking<Unit> {
            val app = startApp()
            val viewModel = buildViewModel(app)
            val recorder =
                app.newRecorder().record(HasSeenWelcomeChanged)

            // The stored default is published on startup.
            assertEquals(
                false,
                recorder.expectState(HasSeenWelcomeChanged),
            )

            // Arrange a set flag first, exactly as launch would.
            app.component.dataStoreManager.setHasSeenWelcome(true)
            assertEquals(
                true,
                recorder.expectState(HasSeenWelcomeChanged),
            )

            viewModel.clearWelcomeFlag()

            // The clear round-trips the real store and reaches the bus.
            assertEquals(
                false,
                recorder.expectState(HasSeenWelcomeChanged),
            )
        }

    @Test
    fun `readLogsForExport flushes so the export is complete`() =
        runBlocking<Unit> {
            val app = startApp()
            val viewModel = buildViewModel(app)

            // Enqueue a line but do NOT flush manually: awaiting the
            // background writer is the method's own contract.
            val historyMarker = "export-marker-${System.nanoTime()}"
            app.component.logManager.info(historyMarker)

            val exportedLogs = viewModel.readLogsForExport()

            assertTrue(
                "export must contain the line logged before it",
                exportedLogs.contains(historyMarker),
            )
        }

    @Test
    fun `clearLogs clears history, announces it, and audits itself`() =
        runBlocking<Unit> {
            val app = startApp()
            val viewModel = buildViewModel(app)
            val logManager = app.component.logManager
            val recorder = app.newRecorder().record(LogsCleared)

            // Establish history on disk we can watch disappear.
            val historyMarker = "history-marker-${System.nanoTime()}"
            logManager.info(historyMarker)
            logManager.flush()
            assertTrue(
                "the marker must be on disk before the clear",
                logManager.readLogContents().contains(historyMarker),
            )

            viewModel.clearLogs()

            // The signal fires once the delete actually happened.
            recorder.expectEvent(LogsCleared)

            // The audit line is enqueued after the clear, so a flush
            // makes the post-clear file contents deterministic.
            logManager.flush()
            val logsAfterClear = logManager.readLogContents()
            assertFalse(
                "history must be gone after the clear",
                logsAfterClear.contains(historyMarker),
            )
            assertTrue(
                "the clear must leave its own audit line",
                logsAfterClear.contains("Log file cleared from Settings"),
            )
        }
}
