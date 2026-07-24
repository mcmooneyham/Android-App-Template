package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.logManager.LogFileSettings
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.logManager.LoggedError
import com.mattmooneyham.base.android.testkit.FakeCrashReporter
import com.mattmooneyham.base.android.testkit.TestAppContext
import com.mattmooneyham.base.android.testkit.awaitTrue
import java.io.File
import java.lang.ref.Reference
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Probe key for funnel assertions; test-local like the contract
// spec's keys.
private object ReportingProbeChanged : StateKey<String>(
    eventName = "reporting.ProbeChanged",
    payloadType = String::class,
)

/**
 * The LogManager as telemetry funnel, end to end through a real
 * AppComponent with a [FakeCrashReporter] at the seam: ERROR lines
 * become counted non-fatals (real throwable or call-site-stamped
 * [LoggedError]), bus triggers and WARN+ lines become breadcrumbs
 * even at release log levels, level filtering admits everything at
 * and above the minimum, ERROR stack traces reach the file, the
 * bounded ERROR drain loses nothing, and failed file writes report
 * once and leave an honest marker.
 */
class LogManagerReportingSpec {

    private val crashReporter = FakeCrashReporter()
    private var testContext: TestAppContext? = null

    private fun startApp(
        minimumLogLevel: LogLevel = LogLevel.DEBUG,
    ): TestAppContext = TestAppContext(
        minimumLogLevel = minimumLogLevel,
        crashReporter = crashReporter,
    ).also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `a throwing listener becomes exactly one counted non-fatal`() {
        val app = startApp()
        val listenerFailure = IllegalStateException("listener blew up")
        val listenerOwner = Any()
        app.component.eventManager.listenTo(
            ReportingProbeChanged,
            listenerOwner,
        ) { throw listenerFailure }

        // Unconfined Main delivers synchronously; the EventManager
        // catches, logs at ERROR with the throwable, and the funnel
        // forwards that same throwable.
        app.component.eventManager.trigger(ReportingProbeChanged, "go")

        assertEquals(
            listOf<Throwable>(listenerFailure),
            crashReporter.recordedNonFatals,
        )
        Reference.reachabilityFence(listenerOwner)
    }

    @Test
    fun `an ERROR without a throwable becomes a call-site LoggedError`() {
        val app = startApp()

        app.component.logManager.error("counted evidence line")

        assertEquals(1, crashReporter.recordedNonFatals.size)
        val forwardedNonFatal = crashReporter.recordedNonFatals.single()
        assertTrue(forwardedNonFatal is LoggedError)
        assertTrue(
            forwardedNonFatal.message.orEmpty()
                .contains("counted evidence line"),
        )
        // The synthesized one-frame stack points at THIS call site,
        // so crash backends group per site instead of one bucket.
        assertEquals(
            "LogManagerReportingSpec",
            forwardedNonFatal.stackTrace.first().className,
        )
    }

    @Test
    fun `levels below the minimum are filtered, at and above print`() {
        val app = startApp(minimumLogLevel = LogLevel.WARN)
        val logManager = app.component.logManager

        logManager.debug("filtered-debug-marker")
        logManager.info("filtered-info-marker")
        logManager.warn("printed-warn-marker")
        logManager.error(
            "printed-error-marker",
            throwable = RuntimeException("stack-trace-marker"),
        )
        runBlocking { logManager.flush() }

        val logContents = logManager.readLogContents()
        assertFalse(logContents.contains("filtered-debug-marker"))
        assertFalse(logContents.contains("filtered-info-marker"))
        assertTrue(logContents.contains("printed-warn-marker"))
        assertTrue(logContents.contains("printed-error-marker"))
        // The ERROR's throwable prints its stack trace with the line.
        assertTrue(
            logContents.contains(
                "java.lang.RuntimeException: stack-trace-marker",
            ),
        )
    }

    @Test
    fun `bus triggers become breadcrumbs even at release log levels`() {
        // INFO is the release shape: DEBUG traces stay out of the
        // file, but the crash ring still gets every trigger.
        val app = startApp(minimumLogLevel = LogLevel.INFO)
        crashReporter.recordedBreadcrumbs.clear()

        app.component.eventManager.trigger(
            ReportingProbeChanged,
            "release probe",
        )

        assertTrue(
            crashReporter.recordedBreadcrumbs.any { breadcrumb ->
                breadcrumb.contains("reporting.ProbeChanged")
            },
        )
        runBlocking { app.component.logManager.flush() }
        assertFalse(
            app.component.logManager.readLogContents()
                .contains("reporting.ProbeChanged"),
        )
    }

    @Test
    fun `warn lines join the breadcrumb trail`() {
        val app = startApp()
        crashReporter.recordedBreadcrumbs.clear()

        app.component.logManager.warn("breadcrumbed warning")

        assertTrue(
            crashReporter.recordedBreadcrumbs.any { breadcrumb ->
                breadcrumb.contains("breadcrumbed warning")
            },
        )
    }

    @Test
    fun `an error burst stalls nothing and loses nothing`() {
        // The bounded ERROR drain may leave queued lines to the
        // background writer; what it must never do is lose them.
        val app = startApp()
        val logManager = app.component.logManager

        repeat(BURST_LINE_COUNT) { lineIndex ->
            logManager.info("burst line $lineIndex")
        }
        logManager.error("burst error line")
        runBlocking { logManager.flush() }

        val logContents = logManager.readLogContents()
        assertTrue(logContents.contains("burst line 0"))
        assertTrue(
            logContents.contains("burst line ${BURST_LINE_COUNT - 1}"),
        )
        assertTrue(logContents.contains("burst error line"))
    }

    @Test
    fun `file write failures report once and leave an honest marker`() {
        // The log "directory" is a FILE, so every append fails.
        val blockerFile =
            Files.createTempFile("not-a-directory-", ".tmp").toFile()
        val reporter = FakeCrashReporter()
        val logManager = LogManager(
            fileSettings = LogFileSettings(
                directoryPath = blockerFile.absolutePath,
            ),
            crashReporter = reporter,
        )
        try {
            // ERROR drains synchronously, so both failed appends run
            // promptly; the second must not add a second non-fatal
            // (once per process is signal, repeats are noise). Each
            // ERROR itself also forwards one LoggedError non-fatal,
            // so count only the IO failures by type.
            logManager.info("first failing line")
            logManager.error("second failing line")
            awaitTrue("the write failure reaches the crash backend") {
                reporter.recordedNonFatals.any { nonFatal ->
                    nonFatal !is LoggedError
                }
            }
            logManager.error("third failing line")
            Thread.sleep(150)
            assertEquals(
                "a full disk must report exactly ONE IO non-fatal",
                1,
                reporter.recordedNonFatals
                    .count { nonFatal -> nonFatal !is LoggedError },
            )

            // "Fix the disk": the blocker becomes a real directory;
            // the next append lands the honest marker first.
            check(blockerFile.delete() && blockerFile.mkdirs())
            logManager.error("post-recovery line")
            // One await for both: the marker and the line are two
            // sequential writes, and a poll can land between them.
            awaitTrue("the marker and the line land after recovery") {
                val logContents = logManager.readLogContents()
                logContents.contains("file write(s) failed") &&
                    logContents.contains("post-recovery line")
            }
        } finally {
            logManager.close()
            blockerFile.deleteRecursively()
        }
    }

    private companion object {
        const val BURST_LINE_COUNT = 200
    }
}
