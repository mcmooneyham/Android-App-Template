package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.EventManager
import com.mattmooneyham.base.android.managers.SignalKey
import com.mattmooneyham.base.android.managers.StateKey
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Kit self-test keys; see EventManagerContractSpec for the pattern.
private object RecorderStepChanged : StateKey<String>(
    eventName = "recorder.StepChanged",
    payloadType = String::class,
)

private object RecorderPulseFired : SignalKey(
    eventName = "recorder.PulseFired",
)

/**
 * Self-test of the assertion kit on a bare [EventManager]: the
 * recorder's queues, order log, and failure messages are themselves
 * contracts the specs rely on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestEventRecorderSpec {

    private lateinit var eventManager: EventManager
    private lateinit var recorder: TestEventRecorder

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        eventManager = EventManager()
        recorder = TestEventRecorder(eventManager)
            .record(RecorderStepChanged)
            .record(RecorderPulseFired)
    }

    @After
    fun tearDown() {
        eventManager.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `expectEvent returns queued payloads in delivery order`() =
        runBlocking<Unit> {
            eventManager.trigger(RecorderStepChanged, "one")
            eventManager.trigger(RecorderStepChanged, "two")

            assertEquals("one", recorder.expectState(RecorderStepChanged))
            assertEquals("two", recorder.expectState(RecorderStepChanged))
        }

    @Test
    fun `expectEvent fails with a diagnostic when nothing arrives`() =
        runBlocking<Unit> {
            val failure = runCatching {
                recorder.expectEvent(
                    RecorderPulseFired,
                    timeout = 100.milliseconds,
                )
            }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertTrue(
                failure?.message.orEmpty()
                    .contains(RecorderPulseFired.eventName),
            )
        }

    @Test
    fun `assertOrder accepts a subsequence and rejects a violation`() {
        eventManager.trigger(RecorderStepChanged, "start")
        eventManager.trigger(RecorderPulseFired)
        eventManager.trigger(RecorderStepChanged, "finish")

        // Subsequence with an interleaved delivery in between: passes.
        recorder.assertOrder(RecorderStepChanged, RecorderStepChanged)
        recorder.assertOrder(RecorderPulseFired, RecorderStepChanged)

        // A key that never fired after the last match: fails.
        val violation = runCatching {
            recorder.assertOrder(RecorderStepChanged, RecorderStepChanged,
                RecorderPulseFired)
        }.exceptionOrNull()
        assertTrue(violation is AssertionError)
    }

    @Test
    fun `assertNoEvent passes when quiet and fails on a delivery`() {
        recorder.assertNoEvent(
            RecorderPulseFired,
            grace = 50.milliseconds,
        )

        eventManager.trigger(RecorderPulseFired)
        val failure = runCatching {
            recorder.assertNoEvent(
                RecorderPulseFired,
                grace = 50.milliseconds,
            )
        }.exceptionOrNull()
        assertTrue(failure is AssertionError)
    }

    @Test
    fun `recordedPayloadsOf snapshots one key's full history`() {
        eventManager.trigger(RecorderStepChanged, "one")
        eventManager.trigger(RecorderPulseFired)
        eventManager.trigger(RecorderStepChanged, "two")

        assertEquals(
            listOf<Any>("one", "two"),
            recorder.recordedPayloadsOf(RecorderStepChanged),
        )
        assertEquals(
            listOf<Any>(TestEventRecorder.SignalFired),
            recorder.recordedPayloadsOf(RecorderPulseFired),
        )
    }
}
