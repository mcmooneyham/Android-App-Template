package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.jokeManager.JokeAutoRetryOnReconnectFlag
import com.mattmooneyham.base.android.managers.jokeManager.JokeStateChanged
import com.mattmooneyham.base.android.managers.jokeManager.JokeStatus
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagsChanged
import com.mattmooneyham.base.android.managers.featureFlagManager.FlagSource
import com.mattmooneyham.base.android.managers.featureFlagManager.ResolvedFlag
import com.mattmooneyham.base.android.testkit.FakeJokeApi
import com.mattmooneyham.base.android.testkit.TestAppContext
import com.mattmooneyham.base.android.testkit.TestEventRecorder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Specification of the R5 choreography exemplar: JokeManager listens
 * to NetworkConnectivityChanged and, when the LAST fetch failed and
 * connectivity transitions from false to true, auto-refreshes exactly
 * once per failure. The whole conversation happens via published
 * events, driven end to end through the fake connectivity boundary.
 *
 * The choreography is gated by [JokeAutoRetryOnReconnectFlag], which
 * is OFF by default: the reconnect tests first enable it through a
 * debug override, and the last test specifies the default-off
 * behavior.
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

    /**
     * Turns the auto-retry flag on via a debug override and waits for
     * the resolution to land (setOverride is fire-and-forget through
     * the manager's confinement). The recorder must be recording
     * [FeatureFlagsChanged].
     */
    private suspend fun enableAutoRetry(
        app: TestAppContext,
        recorder: TestEventRecorder,
    ) {
        app.component.featureFlagManager.setOverride(
            JokeAutoRetryOnReconnectFlag,
            true,
        )
        recorder.expectStateMatching(FeatureFlagsChanged) { snapshot ->
            snapshot.flagsByKey[JokeAutoRetryOnReconnectFlag.flagKey] ==
                ResolvedFlag(enabled = true, source = FlagSource.OVERRIDE)
        }
    }

    @Test
    fun `after a failure, a connectivity flap refreshes exactly once`() =
        runBlocking<Unit> {
            // The startup fetch (start()) fails; the reply queue
            // then falls back to success for the automatic retry.
            jokeApi.enqueueConnectionFailure()
            val app = startApp()
            val recorder = app.newRecorder()
                .record(JokeStateChanged)
                .record(FeatureFlagsChanged)

            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.FAILED
            }
            assertEquals(1, jokeApi.requestCount)

            // The retry ticket was armed by the failure; the flag is
            // read at reaction time, so enabling it now still lets the
            // next reconnect consume the ticket.
            enableAutoRetry(app, recorder)

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
            val recorder = app.newRecorder()
                .record(JokeStateChanged)
                .record(FeatureFlagsChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }
            assertEquals(1, jokeApi.requestCount)

            // Flag ON, so what this test proves is the retry TICKET
            // gating: no failure means no ticket, means no refresh.
            enableAutoRetry(app, recorder)

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

    @Test
    fun `by default the flag is off and a failure plus flap does nothing`() =
        runBlocking<Unit> {
            jokeApi.enqueueConnectionFailure()
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.FAILED
            }
            assertEquals(1, jokeApi.requestCount)

            // No override: the compiled default (false) gates the
            // reaction at decision time, so the reconnect that would
            // normally consume the retry ticket refreshes nothing.
            app.connectivityMonitor.setConnected(false)
            app.connectivityMonitor.setConnected(true)
            recorder.assertNoEvent(JokeStateChanged)
            assertEquals(1, jokeApi.requestCount)
        }
}
