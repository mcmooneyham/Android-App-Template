package com.mattmooneyham.base.android.managers.connectivityManager

import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventLifetime
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.logManager.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// State: whether the device has a usable, validated network path.
// APP lifetime: connectivity is a device fact, not user state, so its
// cached value survives resetSessionReplayCaches on logout. The key
// name and eventName predate the manager's rename from NetworkManager
// and stay unchanged: eventName is a wire/log contract (payload
// evolution rule 5), and the object name follows it.
object NetworkConnectivityChanged : StateKey<Boolean>(
    eventName = "network.ConnectivityChanged",
    payloadType = Boolean::class,
    lifetime = EventLifetime.APP,
)

/**
 * Owns connectivity state for the app: state, events, and logging. The
 * platform hookup lives behind the injected [ConnectivityMonitor]
 * boundary (AndroidConnectivityMonitor in :app's platform package is
 * the real one; tests inject a fake and drive changes by hand).
 *
 * The name deliberately matches android.net.ConnectivityManager: this
 * module has no Android classpath, so the two can never collide here,
 * and the one file that touches both (the :app adapter) imports the
 * platform type explicitly.
 *
 * Provided as a singleton via AppComponent (in :app); monitoring
 * starts on construction.
 */
class ConnectivityManager(
    private val logManager: LogManager,
    private val eventManager: EventManager,
    private val connectivityMonitor: ConnectivityMonitor,
) : ConfinedManager(
    managerName = "ConnectivityManager",
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
                // Publish CHANGES only (the platform reports duplicate
                // states routinely). The bus also suppresses unchanged
                // state; gating at the source additionally skips the
                // log line and the trigger overhead, and keeps the
                // stream strictly alternating so edge detection stays
                // safe over the lossy DROP_OLDEST buffer.
                if (isConnected != mutableConnectivityState.value) {
                    logManager.info(
                        if (isConnected) "Network available"
                        else "Network lost",
                    )
                    publishConnectivity(isConnected)
                }
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
