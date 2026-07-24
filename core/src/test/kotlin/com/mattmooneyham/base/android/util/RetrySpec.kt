package com.mattmooneyham.base.android.util

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The retry utility's full contract. Delay VALUES are asserted
 * through the onRetry hook (they are computed, hence deterministic);
 * wall time is kept tiny so the suite stays fast.
 */
class RetrySpec {

    // Millisecond-scale policy: real delays, negligible wall time.
    private fun quickPolicy(
        maxAttempts: Int? = 3,
        initialDelay: Duration = 1.milliseconds,
        maxDelay: Duration = 8.milliseconds,
    ) = RetryPolicy(
        maxAttempts = maxAttempts,
        initialDelay = initialDelay,
        maxDelay = maxDelay,
    )

    @Test
    fun `a first-try success runs once and waits for nothing`() =
        runBlocking<Unit> {
            var executionCount = 0
            var retryHookFired = false

            val result = retry(
                policy = quickPolicy(),
                onRetry = { _, _, _ -> retryHookFired = true },
            ) {
                executionCount += 1
                "worked"
            }

            assertEquals("worked", result)
            assertEquals(1, executionCount)
            assertTrue(!retryHookFired)
        }

    @Test
    fun `transient failures are retried until success`() =
        runBlocking<Unit> {
            var executionCount = 0

            val result = retry(policy = quickPolicy(maxAttempts = 5)) {
                executionCount += 1
                if (executionCount < 3) {
                    throw IOException("transient $executionCount")
                }
                "recovered"
            }

            assertEquals("recovered", result)
            assertEquals(3, executionCount)
        }

    @Test
    fun `the LAST failure rethrows once attempts are exhausted`() =
        runBlocking<Unit> {
            var executionCount = 0
            val finalFailure = IOException("failure 3")

            try {
                retry<Unit>(policy = quickPolicy(maxAttempts = 3)) {
                    executionCount += 1
                    if (executionCount == 3) throw finalFailure
                    throw IOException("failure $executionCount")
                }
                fail("retry must rethrow after max attempts")
            } catch (rethrown: IOException) {
                assertSame(finalFailure, rethrown)
            }
            assertEquals(3, executionCount)
        }

    @Test
    fun `a non-retryable failure rethrows immediately`() =
        runBlocking<Unit> {
            var executionCount = 0
            val permanentFailure =
                IllegalArgumentException("HTTP 404: retrying is futile")

            try {
                retry<Unit>(
                    policy = quickPolicy(maxAttempts = 5),
                    isRetryable = { failure -> failure is IOException },
                ) {
                    executionCount += 1
                    throw permanentFailure
                }
                fail("retry must rethrow non-retryable failures")
            } catch (rethrown: IllegalArgumentException) {
                assertSame(permanentFailure, rethrown)
            }
            assertEquals(1, executionCount)
        }

    @Test
    fun `cancellation is never retried, even when marked retryable`() =
        runBlocking<Unit> {
            var executionCount = 0

            try {
                retry<Unit>(
                    policy = quickPolicy(maxAttempts = 5),
                    isRetryable = { true },
                ) {
                    executionCount += 1
                    throw CancellationException("teardown")
                }
                fail("cancellation must propagate")
            } catch (cancellation: CancellationException) {
                assertEquals("teardown", cancellation.message)
            }
            assertEquals(1, executionCount)
        }

    @Test
    fun `backoff doubles per retry and caps at maxDelay`() =
        runBlocking<Unit> {
            val observedDelays = mutableListOf<Duration>()
            var executionCount = 0

            retry(
                policy = quickPolicy(maxAttempts = 6),
                onRetry = { _, _, nextDelay ->
                    observedDelays += nextDelay
                },
            ) {
                executionCount += 1
                if (executionCount < 6) throw IOException("again")
                "done"
            }

            // 1, 2, 4, capped at 8, still 8.
            assertEquals(
                listOf(
                    1.milliseconds,
                    2.milliseconds,
                    4.milliseconds,
                    8.milliseconds,
                    8.milliseconds,
                ),
                observedDelays,
            )
        }

    @Test
    fun `onRetry reports one-based attempt numbers and the failure`() =
        runBlocking<Unit> {
            val observedAttempts = mutableListOf<Int>()
            val observedMessages = mutableListOf<String?>()
            var executionCount = 0

            retry(
                policy = quickPolicy(maxAttempts = 3),
                onRetry = { attempt, failure, _ ->
                    observedAttempts += attempt
                    observedMessages += failure.message
                },
            ) {
                executionCount += 1
                if (executionCount < 3) {
                    throw IOException("boom $executionCount")
                }
                "done"
            }

            assertEquals(listOf(1, 2), observedAttempts)
            assertEquals(listOf("boom 1", "boom 2"), observedMessages)
        }

    @Test
    fun `retryForever resubscribes a failing stream until it lives`() =
        runBlocking<Unit> {
            var executionCount = 0

            retryForever(policy = quickPolicy(maxAttempts = null)) { _ ->
                executionCount += 1
                if (executionCount < 3) {
                    throw IOException("stream died $executionCount")
                }
                // Third life completes normally: retryForever returns.
            }

            assertEquals(3, executionCount)
        }

    @Test
    fun `a healthy signal resets both the backoff and the attempt`() =
        runBlocking<Unit> {
            val observedDelays = mutableListOf<Duration>()
            var executionCount = 0

            retryForever(
                // maxAttempts 2: only the healthy-reset lets the
                // third failure retry, proving the attempt count
                // resets alongside the delay.
                policy = quickPolicy(maxAttempts = 2),
                onRetry = { _, _, nextDelay ->
                    observedDelays += nextDelay
                },
            ) { onHealthy ->
                executionCount += 1
                when (executionCount) {
                    1 -> throw IOException("unhealthy failure 1")
                    2 -> {
                        onHealthy()
                        throw IOException("failure after recovery")
                    }
                    else -> Unit
                }
            }

            // Second wait restarts at the initial delay, not doubled.
            assertEquals(
                listOf(1.milliseconds, 1.milliseconds),
                observedDelays,
            )
            assertEquals(3, executionCount)
        }

    @Test
    fun `retryForever propagates cancellation immediately`() =
        runBlocking<Unit> {
            var executionCount = 0

            try {
                retryForever(policy = quickPolicy(maxAttempts = null)) {
                    executionCount += 1
                    throw CancellationException("component closed")
                }
                fail("cancellation must propagate")
            } catch (cancellation: CancellationException) {
                assertEquals("component closed", cancellation.message)
            }
            assertEquals(1, executionCount)
        }

    @Test
    fun `a bounded retryForever gives up like retry does`() =
        runBlocking<Unit> {
            var executionCount = 0

            try {
                retryForever(policy = quickPolicy(maxAttempts = 2)) {
                    executionCount += 1
                    throw IOException("still broken")
                }
                fail("a bounded policy must eventually rethrow")
            } catch (rethrown: IOException) {
                assertEquals("still broken", rethrown.message)
            }
            assertEquals(2, executionCount)
        }

    @Test
    fun `nonsensical policies are rejected at construction`() {
        val invalidConstructions = listOf<() -> RetryPolicy>(
            { RetryPolicy(maxAttempts = 0) },
            { RetryPolicy(initialDelay = 0.milliseconds) },
            {
                RetryPolicy(
                    initialDelay = 10.milliseconds,
                    maxDelay = 5.milliseconds,
                )
            },
            { RetryPolicy(backoffMultiplier = 0.5) },
        )
        invalidConstructions.forEach { construct ->
            try {
                construct()
                fail("policy validation must reject this")
            } catch (expected: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
