package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.testkit.FakeCrashReporter
import com.mattmooneyham.base.android.testkit.TestAppContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The R6 crash-safety mechanisms, end to end through a real
 * AppComponent: the uncaught-exception handler chain (report the
 * fatal, log it, flush, delegate, restore on close), the ERROR-level
 * persistence policy, and size-capped log rotation.
 */
class LogManagerCrashSafetySpec {

    private val crashReporter = FakeCrashReporter()
    private var testContext: TestAppContext? = null

    // Whatever the JVM had installed before this spec touched the
    // global default handler; restored unconditionally in teardown.
    private val originalDefaultHandler =
        Thread.getDefaultUncaughtExceptionHandler()

    private fun startApp(
        maxLogFileSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_FILE_BYTES,
    ): TestAppContext = TestAppContext(
        crashReporter = crashReporter,
        maxLogFileSizeBytes = maxLogFileSizeBytes,
    ).also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
        Thread.setDefaultUncaughtExceptionHandler(originalDefaultHandler)
    }

    @Test
    fun `an uncaught exception is reported, persisted, and delegated`() {
        // A recording "previous" handler, installed BEFORE the
        // component so its handler chains in front of this one.
        val delegatedCrashes = mutableListOf<Throwable>()
        val previousHandler =
            Thread.UncaughtExceptionHandler { _, thrownByCrash ->
                delegatedCrashes += thrownByCrash
            }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        val app = startApp()

        val simulatedCrash = RuntimeException("simulated crash")
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), simulatedCrash)

        // Reported to the crash backend and delegated onward (in
        // production the delegate is the process-killing platform
        // handler).
        assertEquals(listOf(simulatedCrash), crashReporter.recordedFatals)
        assertEquals(listOf(simulatedCrash), delegatedCrashes)

        // The crash line reached the log file. flush() only covers the
        // one command the writer may have claimed before flushForCrash
        // could drain it; everything else was already forced to disk.
        runBlocking { app.component.logManager.flush() }
        val logContents = app.component.logManager.readLogContents()
        assertTrue(
            logContents.contains("Uncaught exception on thread"),
        )
        assertTrue(logContents.contains("simulated crash"))

        // close() restores exactly the handler it had replaced.
        app.close()
        assertSame(
            previousHandler,
            Thread.getDefaultUncaughtExceptionHandler(),
        )
    }

    @Test
    fun `an error line reaches the file without an explicit flush`() {
        val app = startApp()

        app.component.logManager.error("post-mortem evidence line")

        // The ERROR policy drains the write queue synchronously, but is
        // best-effort: the parked writer may claim the line first and
        // write it on the IO pool instead. Assert prompt persistence
        // with a bounded poll, never calling flush().
        val deadline =
            System.currentTimeMillis() + PROMPT_WRITE_TIMEOUT_MILLIS
        var logContents = app.component.logManager.readLogContents()
        while (!logContents.contains("post-mortem evidence line") &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            logContents = app.component.logManager.readLogContents()
        }
        assertTrue(logContents.contains("post-mortem evidence line"))
    }

    @Test
    fun `the log file rotates into a single history file at the cap`() {
        // A tiny cap: a handful of ~150-byte lines crosses it several
        // times over, exercising rotation and history replacement.
        val app = startApp(maxLogFileSizeBytes = TINY_ROTATION_CAP_BYTES)

        repeat(FILLER_LINE_COUNT) { lineIndex ->
            app.component.logManager.info(
                "rotation filler line $lineIndex",
            )
        }
        runBlocking { app.component.logManager.flush() }

        // The live file keeps appending while exactly one ".1" history
        // file holds the previous generation.
        val liveLogFile = File(
            app.filesDirectory,
            LogManager.DEFAULT_LOG_FILE_NAME,
        )
        val rotatedLogFile = File(app.filesDirectory, "base-app.1.log")
        assertTrue(liveLogFile.exists())
        assertTrue(rotatedLogFile.exists())
        assertTrue(
            app.filesDirectory
                .listFiles { file -> file.name.endsWith(".log") }!!
                .size == 2,
        )
    }

    private companion object {
        const val TINY_ROTATION_CAP_BYTES = 512L
        const val FILLER_LINE_COUNT = 30
        const val PROMPT_WRITE_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 10L
    }
}
