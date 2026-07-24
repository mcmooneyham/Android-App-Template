package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.JokeStateChanged
import com.mattmooneyham.base.android.managers.JokeStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke specification for the harness itself: [TestAppContext] must
 * build a REAL, fully started AppComponent from boundary fakes alone,
 * and tear all of it down again.
 */
class TestAppContextSpec {

    private val jokeApi = FakeJokeApi()
    private var testContext: TestAppContext? = null

    private fun startApp(): TestAppContext =
        TestAppContext(jokeApi = jokeApi).also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `it builds a working component from boundary fakes alone`() =
        runBlocking<Unit> {
            val app = startApp()

            // The construction-time joke fetch hit the mock engine
            // (never the network) and completed the real lifecycle.
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }
            assertEquals(1, jokeApi.requestCount)

            // The connectivity boundary was started by NetworkManager.
            assertTrue(app.connectivityMonitor.isStarted)

            // Log lines land inside the per-test directory, stamped by
            // the pinned virtual clock.
            app.component.logManager.flush()
            val logFilePath = app.component.logManager.logFilePath
            assertTrue(
                logFilePath != null && logFilePath.startsWith(
                    app.filesDirectory.absolutePath,
                ),
            )
            val logContents = app.component.logManager.readLogContents()
            assertTrue(logContents.contains("2026-01-01"))
            assertTrue(logContents.contains("AppComponent constructed"))
        }

    @Test
    fun `close tears down the component and releases the fakes`() {
        val app = startApp()

        app.close()

        assertTrue(app.connectivityMonitor.isStopped)
        assertFalse(app.filesDirectory.exists())
    }

    @Test
    fun `constructing the component performs no network IO`() {
        // The behavioral fence for the init budget: every manager's
        // HTTP rides the one MockEngine seam, so ANY constructor that
        // sneaks in a fetch fails this, whatever the call is named.
        val app = TestAppContext(jokeApi = jokeApi, autoStart = false)
        testContext = app

        assertEquals(
            "Construction must not fetch; first IO belongs to " +
                "start() (see the init budget in ConfinedManager)",
            0,
            jokeApi.requestCount,
        )

        app.component.start()
        awaitTrue("the first fetch happens on start") {
            jokeApi.requestCount == 1
        }
    }
}
