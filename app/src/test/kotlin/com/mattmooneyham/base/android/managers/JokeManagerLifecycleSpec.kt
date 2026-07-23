package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.api.FailureKind
import com.mattmooneyham.base.android.testkit.FakeJokeApi
import com.mattmooneyham.base.android.testkit.TestAppContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JokeManager lifecycle, specified against a REAL AppComponent with a
 * scripted [FakeJokeApi] behind the HTTP boundary: REFRESHING to a
 * terminal SUCCESS or FAILED, the in-flight guard, and retention of
 * the last good joke through later failures.
 */
class JokeManagerLifecycleSpec {

    private val jokeApi = FakeJokeApi()
    private var testContext: TestAppContext? = null

    /** Builds the component; script [jokeApi] BEFORE calling this. */
    private fun startApp(): TestAppContext =
        TestAppContext(jokeApi = jokeApi).also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `the initial fetch publishes refreshing and then success`() =
        runBlocking<Unit> {
            // Hold the response so the REFRESHING phase is observable
            // (with a replaying key the recorder sees it either live or
            // as the cached latest value).
            jokeApi.holdResponses()
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)

            val refreshingState = recorder.expectState(JokeStateChanged)
            assertEquals(JokeStatus.REFRESHING, refreshingState.status)
            assertNull(refreshingState.joke)
            assertNull(refreshingState.failure)

            jokeApi.releaseResponses()
            val successState = recorder.expectState(JokeStateChanged)
            assertEquals(JokeStatus.SUCCESS, successState.status)
            assertEquals(FakeJokeApi.DEFAULT_JOKE, successState.joke)
            assertNull(successState.failure)
        }

    @Test
    fun `refresh requests while a fetch is in flight are ignored`() =
        runBlocking<Unit> {
            jokeApi.holdResponses()
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            assertEquals(
                JokeStatus.REFRESHING,
                recorder.expectState(JokeStateChanged).status,
            )

            // Both land while the first fetch is parked at the gate.
            app.component.jokeManager.refreshJoke()
            app.component.jokeManager.refreshJoke()
            // Give the manager's serial confinement real time to
            // process (and drop) them; a dropped refresh has no
            // completion signal to await.
            delay(200)

            jokeApi.releaseResponses()
            assertEquals(
                JokeStatus.SUCCESS,
                recorder.expectState(JokeStateChanged).status,
            )

            // One request served, one lifecycle published: the
            // duplicates produced neither events nor traffic.
            assertEquals(1, jokeApi.requestCount)
            recorder.assertNoEvent(JokeStateChanged)
        }

    @Test
    fun `a failed refresh keeps the last good joke and typed failure`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            // Drain the construction-time fetch (default: success).
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            jokeApi.enqueueHttpError()
            app.component.jokeManager.refreshJoke()

            val refreshingState = recorder.expectState(JokeStateChanged)
            assertEquals(JokeStatus.REFRESHING, refreshingState.status)
            // The last good joke rides along through the refresh...
            assertEquals(FakeJokeApi.DEFAULT_JOKE, refreshingState.joke)

            val failedState = recorder.expectState(JokeStateChanged)
            assertEquals(JokeStatus.FAILED, failedState.status)
            // ...and through the failure, so screens can keep showing
            // it next to the typed failure reason.
            assertEquals(FakeJokeApi.DEFAULT_JOKE, failedState.joke)
            assertEquals(FailureKind.HTTP, failedState.failure?.kind)
        }

    @Test
    fun `a new refresh is accepted immediately after a terminal state`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            // The in-flight flag clears BEFORE the terminal publish, so
            // a refresh issued the moment SUCCESS is heard must run.
            app.component.jokeManager.refreshJoke()
            assertEquals(
                JokeStatus.REFRESHING,
                recorder.expectState(JokeStateChanged).status,
            )
            assertEquals(
                JokeStatus.SUCCESS,
                recorder.expectState(JokeStateChanged).status,
            )
            assertEquals(2, jokeApi.requestCount)
        }
}
