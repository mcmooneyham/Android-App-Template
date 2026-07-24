package com.mattmooneyham.base.android.managers.connectivityManager

import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventLifetime
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.logManager.LogManager
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
 * Owns connectivity for the app: events and logging. The platform
 * hookup lives behind the injected [ConnectivityMonitor] boundary
 * (AndroidConnectivityMonitor in :app's platform package is the real
 * one; tests inject a fake and drive changes by hand). Consumers
 * observe [NetworkConnectivityChanged] on the bus, the one observation
 * idiom in the app; the manager deliberately exposes no second
 * surface.
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

    // Confined: the last state actually published; null until the
    // monitor's first report so that report lands as a plain fact,
    // never as an edge relative to an assumed value.
    private var lastPublishedConnectivity: Boolean? = null

    init {
        // The monitor's contract delivers the device's CURRENT state
        // during start and every change after; nothing here seeds a
        // guess, so a boot while online can never masquerade as an
        // offline-to-online reconnect edge.
        connectivityMonitor.start { isConnected ->
            // Monitor callbacks arrive on a platform thread; hop onto
            // the manager's confinement so the read-then-publish below
            // is race-free. The serial scope preserves callback order.
            managerScope.launch {
                // Publish CHANGES only (the platform reports duplicate
                // states routinely). The bus also suppresses unchanged
                // state; gating at the source additionally skips the
                // log line, and keeps the stream strictly alternating
                // so edge detection stays safe over the lossy
                // DROP_OLDEST buffer.
                if (isConnected != lastPublishedConnectivity) {
                    logManager.info(
                        if (isConnected) "Network available"
                        else "Network lost",
                    )
                    lastPublishedConnectivity = isConnected
                    eventManager.trigger(
                        NetworkConnectivityChanged,
                        isConnected,
                    )
                }
            }
        }
    }

    /** Stops monitoring, then cancels the manager's coroutines. */
    override fun close() {
        connectivityMonitor.stop()
        super.close()
    }

}
