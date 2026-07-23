package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.testkit.FakeJokeApi
import com.mattmooneyham.base.android.testkit.TestAppContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Specification of the R5 choreography exemplar: JokeManager listens
 * to [NetworkConnectivityChanged] and, when the LAST fetch failed and
 * connectivity transitions from false to true, auto-refreshes exactly
 * once per failure. The managers stay peers throughout: the whole
 * conversation below happens via published events, driven end to end
 * through the fake connectivity boundary.
 */
class JokeConnectivityChoreographySpec {

    private val jokeApi = FakeJokeApi()
    private var testContext: TestAppContext? = null

    private fun startApp(): TestAppContext =
        TestAppContext(jokeApi = jokeApi).also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `after a failure, a connectivity flap refreshes exactly once`() =
        runBlocking<Unit> {
            // The construction-time fetch fails; the reply queue then
            // falls back to success for the automatic retry.
            jokeApi.enqueueConnectionFailure()
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)

            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.FAILED
            }
            assertEquals(1, jokeApi.requestCount)

            // Connectivity comes back: exactly one automatic refresh.
            app.connectivityMonitor.setConnected(false)
            app.connectivityMonitor.setConnected(true)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }
            assertEquals(2, jokeApi.requestCount)

            // The failure's retry ticket is consumed: a second flap
            // must trigger nothing further.
            app.connectivityMonitor.setConnected(false)
            app.connectivityMonitor.setConnected(true)
            recorder.assertNoEvent(JokeStateChanged)
            assertEquals(2, jokeApi.requestCount)
        }

    @Test
    fun `after a success, a connectivity flap triggers no refresh`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }
            assertEquals(1, jokeApi.requestCount)

            app.connectivityMonitor.setConnected(false)
            app.connectivityMonitor.setConnected(true)
            recorder.assertNoEvent(JokeStateChanged)

            // Determinism backstop for the grace-based check above: a
            // manual refresh flushes the manager's serial pipeline; if
            // the flap had wrongly queued a refresh, the count below
            // would be 3 (or extra events would have surfaced).
            app.component.jokeManager.refreshJoke()
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }
            assertEquals(2, jokeApi.requestCount)
        }
}
