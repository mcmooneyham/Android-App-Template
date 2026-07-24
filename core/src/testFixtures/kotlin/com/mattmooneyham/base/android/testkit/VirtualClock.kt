package com.mattmooneyham.base.android.testkit

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Settable wall clock for tests. Injected through AppConfig.clock, it
 * pins every wall-time read (currently the LogManager's line
 * timestamps) to a known instant, so time-dependent output is exact
 * and assertable.
 */
class VirtualClock(
    startAt: Instant = DEFAULT_START_INSTANT,
) : Clock {

    @Volatile
    private var currentInstant: Instant = startAt

    override fun now(): Instant = currentInstant

    /** Moves the clock forward (or back, with a negative duration). */
    fun advanceBy(duration: Duration) {
        currentInstant += duration
    }

    /** Jumps the clock to an exact instant. */
    fun setTo(instant: Instant) {
        currentInstant = instant
    }

    companion object {
        /** Recognizable, obviously-pinned default test instant. */
        val DEFAULT_START_INSTANT: Instant =
            Instant.parse("2026-01-01T00:00:00Z")
    }
}
