package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.api.FailureKind
import com.mattmooneyham.base.android.api.JokeDto
import com.mattmooneyham.base.android.managers.jokeManager.JokeDetailChanged
import com.mattmooneyham.base.android.managers.jokeManager.JokeStateChanged
import com.mattmooneyham.base.android.managers.jokeManager.JokeStatus
import com.mattmooneyham.base.android.testkit.FakeJokeApi
import com.mattmooneyham.base.android.testkit.TestAppContext
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
            // A dropped refresh has no completion signal to await; the
            // fence resumes once the confinement has processed (and
            // dropped) both launches queued above.
            app.component.jokeManager.awaitConfinement()

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
            // Drain the startup fetch (default: success).
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
    fun `a cached detail request answers without a network fetch`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder()
                .record(JokeStateChanged)
                .record(JokeDetailChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            // The startup joke (DEFAULT_JOKE, id 1) is already cached:
            // the keyed request publishes SUCCESS immediately.
            app.component.jokeManager.loadJokeDetail(
                FakeJokeApi.DEFAULT_JOKE.id,
            )

            val detailState = recorder.expectStateMatching(
                JokeDetailChanged,
            ) { detail -> detail.status == JokeStatus.SUCCESS }
            assertEquals(FakeJokeApi.DEFAULT_JOKE, detailState.joke)
            // Exactly one request ever went out: the startup fetch.
            assertEquals(1, jokeApi.requestCount)
        }

    @Test
    fun `an uncached detail request fetches by id and stays keyed`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder()
                .record(JokeStateChanged)
                .record(JokeDetailChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            val keyedJoke = JokeDto(
                id = 7,
                type = "general",
                setup = "What id did the deep link ask for?",
                punchline = "This one.",
            )
            jokeApi.enqueueSuccess(keyedJoke)
            app.component.jokeManager.loadJokeDetail(7)

            // The lifecycle is keyed: every state carries the
            // REQUESTED id, so a detail screen ignores other ids.
            val loadingState = recorder.expectStateMatching(
                JokeDetailChanged,
            ) { detail -> detail.status == JokeStatus.REFRESHING }
            assertEquals(7, loadingState.jokeId)

            val loadedState = recorder.expectStateMatching(
                JokeDetailChanged,
            ) { detail -> detail.status == JokeStatus.SUCCESS }
            assertEquals(keyedJoke, loadedState.joke)
            assertEquals(2, jokeApi.requestCount)
        }

    @Test
    fun `a failed detail fetch publishes a keyed typed failure`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder()
                .record(JokeStateChanged)
                .record(JokeDetailChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            jokeApi.enqueueHttpError()
            app.component.jokeManager.loadJokeDetail(9)

            val failedState = recorder.expectStateMatching(
                JokeDetailChanged,
            ) { detail -> detail.status == JokeStatus.FAILED }
            assertEquals(9, failedState.jokeId)
            assertEquals(FailureKind.HTTP, failedState.failure?.kind)
        }

    @Test
    fun `a timed-out fetch classifies as TIMEOUT, not NETWORK`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            jokeApi.enqueueTimeout()
            app.component.jokeManager.refreshJoke()

            val failedState = recorder.expectStateMatching(
                JokeStateChanged,
            ) { state -> state.status == JokeStatus.FAILED }
            // The call-timeout cap is a first-class failure category:
            // views can say "took too long" instead of "check your
            // connection", and the in-flight flag is provably clear.
            assertEquals(FailureKind.TIMEOUT, failedState.failure?.kind)
        }

    @Test
    fun `an undecodable payload classifies as DECODE`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            jokeApi.enqueueMalformedPayload()
            app.component.jokeManager.refreshJoke()

            val failedState = recorder.expectStateMatching(
                JokeStateChanged,
            ) { state -> state.status == JokeStatus.FAILED }
            // Strict Json (no lenient mode) is what guarantees a wrong
            // payload SURFACES here instead of being silently coerced.
            assertEquals(FailureKind.DECODE, failedState.failure?.kind)
        }

    @Test
    fun `a wrong content-type response classifies as DECODE`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder().record(JokeStateChanged)
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            jokeApi.enqueueWrongContentType()
            app.component.jokeManager.refreshJoke()

            val failedState = recorder.expectStateMatching(
                JokeStateChanged,
            ) { state -> state.status == JokeStatus.FAILED }
            // The captive-portal shape: a 200 whose text/html body
            // never reaches the JSON decoder. Ktor raises
            // NoTransformationFoundException, which must classify as
            // DECODE, not UNKNOWN, so views show "bad data", not a
            // shrug.
            assertEquals(FailureKind.DECODE, failedState.failure?.kind)
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
