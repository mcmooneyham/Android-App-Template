package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.connectivityManager.ConnectivityMonitor

/**
 * Test double for the connectivity boundary. ConnectivityManager
 * starts it during component construction; tests then drive
 * connectivity changes deterministically with [setConnected].
 *
 * Honors the port contract: the first report, delivered during
 * [start], is the monitor's CURRENT state ([initialConnectivity],
 * offline by default), exactly like the real adapter's synchronous
 * capability query.
 *
 * The callback is invoked on the CALLER's thread, mirroring the real
 * monitor's "possibly from a background thread" contract;
 * ConnectivityManager hops onto its own confinement either way, so
 * connectivity-driven assertions must await events rather than assume
 * synchronous publication.
 */
class FakeConnectivityMonitor(
    private val initialConnectivity: Boolean = false,
) : ConnectivityMonitor {

    @Volatile
    private var onConnectivityChanged: ((Boolean) -> Unit)? = null

    /** Whether [stop] has been called (by
     * ConnectivityManager.close()). */
    @Volatile
    var isStopped: Boolean = false
        private set

    /** Whether the manager under test started the monitor. */
    val isStarted: Boolean
        get() = onConnectivityChanged != null

    override fun start(onConnectivityChanged: (Boolean) -> Unit) {
        // Mirror the real monitor: starting twice is a no-op.
        if (this.onConnectivityChanged != null) return
        this.onConnectivityChanged = onConnectivityChanged
        // Port contract: the first report is the CURRENT state,
        // delivered during start.
        onConnectivityChanged(initialConnectivity)
    }

    override fun stop() {
        onConnectivityChanged = null
        isStopped = true
    }

    /** Reports a connectivity change as the platform would. */
    fun setConnected(isConnected: Boolean) {
        val callback = onConnectivityChanged ?: error(
            "setConnected called before the monitor was started; " +
                "construct the component first",
        )
        callback(isConnected)
    }
}
