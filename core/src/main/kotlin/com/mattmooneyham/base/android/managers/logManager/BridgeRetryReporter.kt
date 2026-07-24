package com.mattmooneyham.base.android.managers.logManager

import kotlin.time.Duration

/**
 * Shared onRetry reporter for the retryForever store-to-bus bridges:
 * the FIRST failure of an outage logs at ERROR (a counted non-fatal
 * through the telemetry funnel), repeats log at WARN so a permanently
 * broken disk cannot spam the crash backend. Because onHealthy resets
 * the attempt counter, each NEW outage gets its own single ERROR.
 *
 * [bridgeName] names the failing bridge in the log line (for example
 * "hasSeenWelcome bridge"); keeping the name a parameter is what lets
 * every bridge share this one reporter without mislabeling failures.
 */
fun LogManager.bridgeRetryReporter(
    bridgeName: String,
): (attempt: Int, failure: Throwable, nextDelay: Duration) -> Unit =
    { attempt, failure, nextDelay ->
        if (attempt == 1) {
            error(
                "$bridgeName failed; resubscribing in $nextDelay",
                throwable = failure,
            )
        } else {
            warn(
                "$bridgeName failed again " +
                    "(attempt $attempt); resubscribing in $nextDelay",
            )
        }
    }
