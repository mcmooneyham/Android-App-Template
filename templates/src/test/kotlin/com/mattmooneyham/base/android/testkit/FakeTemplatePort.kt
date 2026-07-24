package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.templateManager.TemplatePort

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// core/src/testFixtures/kotlin/.../testkit/ (fakes live in the
// testkit fixtures so every module's tests can share them), rename
// Template -> YourBoundary, and delete these banners.
// ===================================================================

/**
 * STEP 1: FAKES ARE HAND-WRITTEN, never mocked: a small class that
 * honors the port contract plus DRIVING methods ([emitReading]) that
 * let a spec play the platform's role deterministically.
 *
 * STEP 2: MIRROR THE CONTRACT'S EDGES: starting twice is a no-op,
 * the callback runs on the CALLER's thread (matching the "possibly
 * from a background thread" clause, so specs must await events
 * rather than assume synchronous publication), and [isStarted] /
 * [isStopped] expose the lifecycle for teardown assertions.
 */
class FakeTemplatePort : TemplatePort {

    @Volatile
    private var onReading: ((Int) -> Unit)? = null

    /** Whether [stop] has been called (by the manager's close()). */
    @Volatile
    var isStopped: Boolean = false
        private set

    /** Whether the manager under test started the port. */
    val isStarted: Boolean
        get() = onReading != null

    override fun start(onReading: (Int) -> Unit) {
        if (this.onReading != null) return
        this.onReading = onReading
    }

    override fun stop() {
        onReading = null
        isStopped = true
    }

    /** Delivers a reading as the platform would. */
    fun emitReading(reading: Int) {
        val callback = onReading ?: error(
            "emitReading called before the port was started; " +
                "construct the manager first",
        )
        callback(reading)
    }
}
