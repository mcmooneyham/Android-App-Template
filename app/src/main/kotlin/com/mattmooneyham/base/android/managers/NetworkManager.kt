package com.mattmooneyham.base.android.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * raw ConnectivityManager hookup lives at the bottom of this file.
 *
 * Provided as a singleton via [com.mattmooneyham.base.android.BaseSdk];
 * monitoring starts on construction.
 *
 * @param platformContext any Context (the application context is used).
 */
class NetworkManager(
    private val logManager: LogManager,
    private val eventManager: EventManager,
    platformContext: Any?,
) {

    private val mutableConnectivityState = MutableStateFlow(false)

    /** True while the device has a usable, validated network path. */
    val connectivityState: StateFlow<Boolean> =
        mutableConnectivityState.asStateFlow()

    init {
        // Seed the replay cache BEFORE starting the monitor: the first
        // system update can arrive on another thread immediately, and
        // publishing the initial false afterwards could overwrite it.
        publishConnectivity(mutableConnectivityState.value)
        startPlatformConnectivityMonitoring(platformContext) { isConnected ->
            if (isConnected != mutableConnectivityState.value) {
                logManager.info(
                    if (isConnected) "Network available" else "Network lost",
                )
            }
            publishConnectivity(isConnected)
        }
    }

    /** Stops monitoring; a stopped manager cannot be restarted. */
    fun stopMonitoring() {
        stopPlatformConnectivityMonitoring()
    }

    private fun publishConnectivity(isConnected: Boolean) {
        mutableConnectivityState.value = isConnected
        eventManager.trigger(NetworkConnectivityChanged, isConnected)
    }

}

// Raw ConnectivityManager wiring; all state/event behavior lives in
// NetworkManager above. Module-level state is safe because NetworkManager
// is a process-wide singleton (BaseSdk).
private var systemConnectivityManager: ConnectivityManager? = null
private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

/**
 * Starts the system network monitor; [onConnectivityChanged] receives
 * validated connectivity soon after and on every change, possibly from a
 * background thread. Calling again while active is a no-op.
 */
private fun startPlatformConnectivityMonitoring(
    platformContext: Any?,
    onConnectivityChanged: (Boolean) -> Unit,
) {
    if (activeNetworkCallback != null) return
    val applicationContext =
        (platformContext as? Context)?.applicationContext ?: return

    val connectivityManager = applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onConnectivityChanged(true)
        }

        override fun onLost(network: Network) {
            onConnectivityChanged(false)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            onConnectivityChanged(
                networkCapabilities.hasValidatedInternet(),
            )
        }
    }

    systemConnectivityManager = connectivityManager
    activeNetworkCallback = callback
    connectivityManager.registerDefaultNetworkCallback(callback)
}

/** Stops the system network monitor; it cannot be restarted. */
private fun stopPlatformConnectivityMonitoring() {
    activeNetworkCallback?.let { callback ->
        systemConnectivityManager?.unregisterNetworkCallback(callback)
    }
    activeNetworkCallback = null
    systemConnectivityManager = null
}

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

