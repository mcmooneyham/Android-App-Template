package com.mattmooneyham.base.android.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// State: whether the device has a usable, validated network path.
// APP lifetime: connectivity is a device fact, not user state, so its
// cached value survives resetSessionReplayCaches on logout.
object NetworkConnectivityChanged : StateKey<Boolean>(
    eventName = "network.ConnectivityChanged",
    payloadType = Boolean::class,
    lifetime = EventLifetime.APP,
)

/**
 * Owns connectivity state for the app: state, events, and logging. The
 * platform hookup lives behind the injected [ConnectivityMonitor]
 * boundary (see AndroidConnectivityMonitor for the real one; tests
 * inject a fake and drive changes by hand).
 *
 * Provided as a singleton via
 * [com.mattmooneyham.base.android.di.AppComponent]; monitoring starts
 * on construction.
 */
class NetworkManager(
    private val logManager: LogManager,
    private val eventManager: EventManager,
    private val connectivityMonitor: ConnectivityMonitor,
) : ConfinedManager(
    managerName = "NetworkManager",
    failureLogManager = logManager,
) {

    private val mutableConnectivityState = MutableStateFlow(false)

    /** True while the device has a usable, validated network path. */
    val connectivityState: StateFlow<Boolean> =
        mutableConnectivityState.asStateFlow()

    init {
        // Seed the replay cache BEFORE starting the monitor: the first
        // system update can arrive immediately, and publishing the
        // initial false afterwards could overwrite it. The seed runs on
        // the constructing thread, which is safe: no managerScope
        // coroutine can exist yet.
        publishConnectivity(mutableConnectivityState.value)
        connectivityMonitor.start { isConnected ->
            // Monitor callbacks arrive on a platform thread; hop onto
            // the manager's confinement so the read-then-publish below
            // is race-free. The serial scope preserves callback order.
            managerScope.launch {
                if (isConnected != mutableConnectivityState.value) {
                    logManager.info(
                        if (isConnected) "Network available"
                        else "Network lost",
                    )
                }
                publishConnectivity(isConnected)
            }
        }
    }

    /** Stops monitoring; a stopped manager cannot be restarted. */
    fun stopMonitoring() {
        connectivityMonitor.stop()
    }

    /** Stops monitoring, then cancels the manager's coroutines. */
    override fun close() {
        stopMonitoring()
        super.close()
    }

    private fun publishConnectivity(isConnected: Boolean) {
        mutableConnectivityState.value = isConnected
        eventManager.trigger(NetworkConnectivityChanged, isConnected)
    }

}
