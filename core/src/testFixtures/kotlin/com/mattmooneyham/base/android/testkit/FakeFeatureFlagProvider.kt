package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagProvider

/**
 * Test double for the feature-flag provider boundary.
 * FeatureFlagManager starts it during component construction; tests
 * then push backend values deterministically with [setFlags],
 * mirroring [FakeConnectivityMonitor].
 *
 * The callback is invoked on the CALLER's thread, matching the real
 * contract ("possibly from a background thread"); the manager hops
 * onto its own confinement either way, so flag-driven assertions must
 * await FeatureFlagsChanged
 * events rather than assume synchronous resolution.
 */
class FakeFeatureFlagProvider : FeatureFlagProvider {

    @Volatile
    private var onFlagsUpdated: ((Map<String, Boolean>) -> Unit)? = null

    /** Whether [stop] has been called (by FeatureFlagManager.close()). */
    @Volatile
    var isStopped: Boolean = false
        private set

    /** Whether the manager under test started the provider. */
    val isStarted: Boolean
        get() = onFlagsUpdated != null

    override fun start(onFlagsUpdated: (Map<String, Boolean>) -> Unit) {
        // Mirror the real contract: starting twice is a no-op.
        if (this.onFlagsUpdated != null) return
        this.onFlagsUpdated = onFlagsUpdated
    }

    override fun stop() {
        onFlagsUpdated = null
        isStopped = true
    }

    /** Pushes a full backend value map, as a remote refresh would. */
    fun setFlags(flagValues: Map<String, Boolean>) {
        val callback = onFlagsUpdated ?: error(
            "setFlags called before the provider was started; " +
                "construct the component first",
        )
        callback(flagValues)
    }
}
