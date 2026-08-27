package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.testkit.TestAppContext
import com.mattmooneyham.base.android.testkit.awaitTrue
import java.io.File
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rolling-file retention policy through a real AppComponent:
 * daily dated files driven by the injected clock, age and total-size
 * prunes on the background scope, and the zip export of the whole
 * history.
 */
class LogManagerRollingFileSpec {

    private var testContext: TestAppContext? = null

    @After
    fun tearDown() {
        testContext?.close()
    }

    // Derived from the constant so a rename of the live log file can
    // never silently break these assertions.
    private fun datedLogFileName(date: String): String =
        LogManager.DEFAULT_LOG_FILE_NAME.replace(".log", "-$date.log")

    @Test
    fun `a new UTC day starts a new dated file and keeps the old one`() =
        runBlocking<Unit> {
            val app = TestAppContext().also { testContext = it }
            val logManager = app.component.logManager

            logManager.info("first-day-marker")
            logManager.flush()
            app.clock.advanceBy(24.hours)
            logManager.info("second-day-marker")
            logManager.flush()

            val firstDayFile = File(
                app.logsDirectory,
                datedLogFileName("2026-01-01"),
            )
            val secondDayFile = File(
                app.logsDirectory,
                datedLogFileName("2026-01-02"),
            )
            assertTrue(firstDayFile.exists())
            assertTrue(secondDayFile.exists())
            assertTrue(
                firstDayFile.readText().contains("first-day-marker"),
            )
            assertTrue(
                secondDayFile.readText().contains("second-day-marker"),
            )
        }

    @Test
    fun `expired days are pruned and foreign files are left alone`() =
        runBlocking<Unit> {
            val app = TestAppContext(maxLogRetentionDays = 2)
                .also { testContext = it }
            val logManager = app.component.logManager

            logManager.info("first-day-marker")
            logManager.flush()
            val foreignFile =
                File(app.logsDirectory, "foreign-file.txt")
            foreignFile.writeText("not a log file")

            app.clock.advanceBy(24.hours)
            logManager.info("second-day-marker")
            logManager.flush()
            app.clock.advanceBy(24.hours)
            logManager.info("third-day-marker")
            logManager.flush()

            val firstDayFile = File(
                app.logsDirectory,
                datedLogFileName("2026-01-01"),
            )
            awaitTrue("the expired day's file is pruned") {
                !firstDayFile.exists()
            }
            assertTrue(
                File(app.logsDirectory, datedLogFileName("2026-01-02"))
                    .exists(),
            )
            assertTrue(
                File(app.logsDirectory, datedLogFileName("2026-01-03"))
                    .exists(),
            )
            assertTrue(foreignFile.exists())
        }

    @Test
    fun `the size pass prunes oldest rolls down to the total cap`() =
        runBlocking<Unit> {
            val app = TestAppContext(
                maxLogFileSizeBytes = TINY_ROLL_CAP_BYTES,
                maxLogTotalSizeBytes = TINY_TOTAL_CAP_BYTES,
            ).also { testContext = it }
            val logManager = app.component.logManager

            repeat(FILLER_LINE_COUNT) { lineIndex ->
                logManager.info("size filler line $lineIndex")
            }
            logManager.flush()

            val liveLogFile = File(
                app.logsDirectory,
                datedLogFileName("2026-01-01"),
            )
            val firstRolledLogFile = File(
                app.logsDirectory,
                datedLogFileName("2026-01-01")
                    .replace(".log", ".1.log"),
            )
            awaitTrue(
                "the total settles at most one file over the cap",
            ) {
                totalLogBytes(app) <=
                    TINY_TOTAL_CAP_BYTES + TINY_ROLL_CAP_BYTES
            }
            awaitTrue("the oldest roll is pruned first") {
                !firstRolledLogFile.exists()
            }
            assertTrue(liveLogFile.exists())
        }

    @Test
    fun `readLogContents falls back to the newest file across midnight`() =
        runBlocking<Unit> {
            val app = TestAppContext(
                maxLogFileSizeBytes = TINY_ROLL_CAP_BYTES,
            ).also { testContext = it }
            val logManager = app.component.logManager

            repeat(FILLER_LINE_COUNT) { lineIndex ->
                logManager.info("rolled filler line $lineIndex")
            }
            logManager.info("newest-live-marker")
            logManager.flush()
            app.clock.advanceBy(24.hours)

            assertTrue(
                logManager.logFilePath!!.contains("2026-01-02"),
            )
            // Nothing appended today: the fallback must serve the
            // previous day's LIVE file, which sorts after its rolls.
            assertTrue(
                logManager.readLogContents()
                    .contains("newest-live-marker"),
            )
        }

    @Test
    fun `the export zip holds every day's file oldest first`() =
        runBlocking<Unit> {
            val app = TestAppContext().also { testContext = it }
            val logManager = app.component.logManager

            logManager.info("first-day-marker")
            logManager.flush()
            app.clock.advanceBy(24.hours)
            logManager.info("second-day-marker")
            logManager.flush()

            val exportPath = logManager.writeExportSnapshot()

            assertNotNull("a logged app must have an export", exportPath)
            ZipFile(exportPath!!).use { zipFile ->
                val entryNames = mutableListOf<String>()
                val entryTextsByName = mutableMapOf<String, String>()
                for (zipEntry in zipFile.entries()) {
                    entryNames += zipEntry.name
                    entryTextsByName[zipEntry.name] = zipFile
                        .getInputStream(zipEntry)
                        .use { entryStream ->
                            entryStream.readBytes().decodeToString()
                        }
                }
                assertEquals(
                    listOf(
                        datedLogFileName("2026-01-01"),
                        datedLogFileName("2026-01-02"),
                    ),
                    entryNames,
                )
                assertTrue(
                    entryTextsByName
                        .getValue(datedLogFileName("2026-01-01"))
                        .contains("first-day-marker"),
                )
                assertTrue(
                    entryTextsByName
                        .getValue(datedLogFileName("2026-01-02"))
                        .contains("second-day-marker"),
                )
            }
        }

    private fun totalLogBytes(app: TestAppContext): Long =
        app.logsDirectory.listFiles().orEmpty()
            .sumOf { logFile -> logFile.length() }

    private companion object {
        const val TINY_ROLL_CAP_BYTES = 512L
        const val TINY_TOTAL_CAP_BYTES = 2_048L
        const val FILLER_LINE_COUNT = 60
    }
}
