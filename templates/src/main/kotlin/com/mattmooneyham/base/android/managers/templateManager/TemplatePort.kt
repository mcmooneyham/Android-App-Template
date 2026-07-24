package com.mattmooneyham.base.android.managers.templateManager

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// core/src/main/kotlin/.../managers/<yourFeature>Manager/, rename
// Template -> YourBoundary, and delete these banner comments. The
// port lives in :core BESIDE the manager that owns it; the adapter
// lives in :app (see platform/TemplateAdapter.kt); the fake lives in
// core/src/testFixtures/.../testkit/ (see the copy note in this
// module's test sources).
// ===================================================================

/**
 * STEP 1: DECLARE THE PORT: the thinnest interface over the platform
 * or SDK capability your manager needs, named for the CAPABILITY
 * (never the vendor; the vendor belongs in the adapter's name). The
 * manager owns all state and event behavior; the port owns only the
 * hookup. Because :core has no Android classpath, the compiler
 * guarantees nothing platform-shaped leaks through this seam.
 *
 * STEP 2: PICK THE SHAPE. Two established shapes in this codebase:
 * - start(callback)/stop for push-style sources (ConnectivityMonitor,
 *   FeatureFlagProvider, this one). Document the callback's thread
 *   ("possibly from a background thread"): the manager hops onto its
 *   confinement, so the adapter never needs to.
 * - Plain suspend functions for request/response boundaries.
 *
 * STEP 3: WIRE THE SEAM. Add a field to AppConfig with a safe default
 * (a no-op object or a required parameter), construct the real
 * adapter in BaseApplication, and pass it through:
 *
 * ```
 * // AppConfig.kt
 * val templatePort: TemplatePort = NoOpTemplatePort,
 * // BaseApplication.kt
 * templatePort = AndroidTemplateAdapter(this),
 * ```
 */
interface TemplatePort {

    /**
     * Starts delivering readings. [onReading] may be invoked from any
     * thread; calling start again while active is a no-op.
     */
    fun start(onReading: (Int) -> Unit)

    /** Stops delivering; a stopped port cannot be restarted. */
    fun stop()
}

/** Safe default so tests and previews need no wiring. */
object NoOpTemplatePort : TemplatePort {
    override fun start(onReading: (Int) -> Unit) = Unit
    override fun stop() = Unit
}
