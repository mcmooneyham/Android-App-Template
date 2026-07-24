package com.mattmooneyham.base.android.viewModels

import com.mattmooneyham.base.android.BuildConfig
import com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.managers.logManager.LogsCleared
import com.mattmooneyham.base.android.testkit.TestAppContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        featureFlagManager = app.component.featureFlagManager,
    )

    @Test
    fun `about values come straight from the build config`() {
        val app = startApp()
        val viewModel = buildViewModel(app)

        assertEquals(BuildConfig.VERSION_NAME, viewModel.appVersionName)
        // BUILD_TIMESTAMP_SECONDS is a compile-time constant that the
        // compiler INLINES separately into app and test bytecode, and
        // a stale build cache can give the two units different
        // generations, so cross-unit equality is unassertable. Pin
        // the contract instead: a 10-digit epoch-seconds stamp.
        assertTrue(
            "buildTimestampSeconds must be an epoch-seconds stamp",
            viewModel.buildTimestampSeconds in
                1_600_000_000L..9_999_999_999L,
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
    fun `the export snapshot contains lines logged just before it`() =
        runBlocking<Unit> {
            val app = startApp()
            val viewModel = buildViewModel(app)

            // Enqueue a line but do NOT flush manually: awaiting the
            // background writer is the snapshot's own contract.
            val historyMarker = "export-marker-${System.nanoTime()}"
            app.component.logManager.info(historyMarker)

            val exportPath = viewModel.writeLogExportSnapshot()

            assertNotNull("a logged app must have an export", exportPath)
            assertTrue(
                "the export must contain the line logged before it",
                File(exportPath!!).readText().contains(historyMarker),
            )
        }

    @Test
    fun `the export snapshot includes the rotated history`() =
        runBlocking<Unit> {
            // A tiny rotation cap pushes early lines into the ".1"
            // history file, which readLogContents excludes by
            // contract; the export must still carry them. autoStart
            // off: the joke fetch's async log lines could otherwise
            // force a SECOND rotation that discards the history this
            // test plants.
            val app = TestAppContext(
                maxLogFileSizeBytes = 512L,
                autoStart = false,
            ).also { testContext = it }
            val viewModel = buildViewModel(app)
            val logManager = app.component.logManager

            val earlyMarker = "early-rotated-marker"
            logManager.info(earlyMarker)
            // Fill until the marker rotates out of the LIVE file
            // (bounded: ~4 lines cross the 512-byte cap).
            var fillerIndex = 0
            while (fillerIndex < 20) {
                logManager.info("rotation filler line $fillerIndex")
                fillerIndex += 1
                logManager.flush()
                if (!logManager.readLogContents()
                        .contains(earlyMarker)
                ) {
                    break
                }
            }
            assertFalse(
                "the marker must have rotated out of the live file",
                logManager.readLogContents().contains(earlyMarker),
            )

            val exportPath = viewModel.writeLogExportSnapshot()

            assertNotNull(exportPath)
            assertTrue(
                "the export must include rotated history",
                File(exportPath!!).readText().contains(earlyMarker),
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
