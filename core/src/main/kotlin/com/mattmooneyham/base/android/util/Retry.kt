package com.mattmooneyham.base.android.util

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * How a failed operation is retried: bounded (or unbounded) attempts
 * with capped exponential backoff. The defaults (3 attempts, 500 ms
 * doubling to a 30 s cap) fit transient IO; tune per call site.
 *
 * Scope boundary: this is for IN-PROCESS, TRANSIENT retries (a flaky
 * network call, a failed DataStore read). Work that must survive
 * process death belongs to the durable job pipeline
 * (ARCHITECTURE-SCALING.md), never to a retry loop.
 *
 * @param maxAttempts total executions allowed; null retries forever
 *   (long-lived bridges). Bounded policies rethrow the LAST failure
 *   once exhausted.
 * @param initialDelay wait before the first retry.
 * @param maxDelay ceiling the doubling never exceeds.
 * @param backoffMultiplier growth factor per retry (1.0 = constant).
 */
data class RetryPolicy(
    val maxAttempts: Int? = 3,
    val initialDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 30.seconds,
    val backoffMultiplier: Double = 2.0,
) {
    init {
        require(maxAttempts == null || maxAttempts >= 1) {
            "maxAttempts must be at least 1 (or null for unbounded)"
        }
        require(initialDelay.isPositive()) {
            "initialDelay must be positive"
        }
        require(maxDelay >= initialDelay) {
            "maxDelay must be at least initialDelay"
        }
        require(backoffMultiplier >= 1.0) {
            "backoffMultiplier must be at least 1.0"
        }
    }
}

/**
 * Runs [operation], retrying failures under [policy]. Rules:
 *
 * - [CancellationException] ALWAYS rethrows immediately: teardown is
 *   never retried (and never mistaken for a failure).
 * - A failure [isRetryable] rejects rethrows immediately (HTTP 4xx,
 *   a decode error: retrying cannot help). The default retries every
 *   Exception; Errors are never caught.
 * - Once [RetryPolicy.maxAttempts] executions have failed, the last
 *   failure rethrows to the caller.
 * - [onRetry] fires before each wait with the attempt number, the
 *   failure, and the coming delay: the caller's logging hook.
 *
 * The delay runs in the caller's coroutine, so cancelling the caller
 * (a manager's close()) interrupts a parked retry instantly.
 */
suspend fun <ResultType> retry(
    policy: RetryPolicy = RetryPolicy(),
    isRetryable: (Throwable) -> Boolean = { true },
    onRetry: (
        attempt: Int,
        failure: Throwable,
        nextDelay: Duration,
    ) -> Unit = { _, _, _ -> },
    operation: suspend () -> ResultType,
): ResultType {
    var nextDelay = policy.initialDelay
    var attempt = 1
    while (true) {
        try {
            return operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val attemptsExhausted = policy.maxAttempts != null &&
                attempt >= policy.maxAttempts
            if (attemptsExhausted || !isRetryable(failure)) {
                throw failure
            }
            onRetry(attempt, failure, nextDelay)
            delay(nextDelay)
            nextDelay = (nextDelay * policy.backoffMultiplier)
                .coerceAtMost(policy.maxDelay)
            attempt += 1
        }
    }
}

/**
 * Runs a LONG-LIVED [operation] (typically a flow collection that
 * only returns on failure), resubscribing on every failure under
 * [policy]. For a stream, "success" is not the block returning, it
 * is data flowing: the block receives an [onHealthy] hook and calls
 * it on each successful emission, which resets the backoff (and the
 * attempt count) so the NEXT outage starts from [RetryPolicy
 * .initialDelay] and gets its own first-failure treatment.
 *
 * Returns only when [operation] completes normally. Cancellation
 * rethrows immediately, exactly like [retry]. Every non-cancellation
 * Exception is retried, so a bridge survives any recoverable failure
 * (pass a bounded [policy] to give up instead); Errors are never
 * caught, exactly like [retry].
 *
 * ```
 * managerScope.launch {
 *     retryForever(onRetry = { attempt, failure, delay -> ... }) {
 *         onHealthy ->
 *         store.data.collect { value ->
 *             onHealthy()
 *             eventManager.trigger(SomethingChanged, value)
 *         }
 *     }
 * }
 * ```
 */
suspend fun retryForever(
    policy: RetryPolicy = RetryPolicy(maxAttempts = null),
    onRetry: (
        attempt: Int,
        failure: Throwable,
        nextDelay: Duration,
    ) -> Unit = { _, _, _ -> },
    operation: suspend (onHealthy: () -> Unit) -> Unit,
) {
    var nextDelay = policy.initialDelay
    var attempt = 1
    val resetBackoff: () -> Unit = {
        nextDelay = policy.initialDelay
        attempt = 1
    }
    while (true) {
        try {
            operation(resetBackoff)
            return
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val attemptsExhausted = policy.maxAttempts != null &&
                attempt >= policy.maxAttempts
            if (attemptsExhausted) throw failure
            onRetry(attempt, failure, nextDelay)
            delay(nextDelay)
            nextDelay = (nextDelay * policy.backoffMultiplier)
                .coerceAtMost(policy.maxDelay)
            attempt += 1
        }
    }
}
