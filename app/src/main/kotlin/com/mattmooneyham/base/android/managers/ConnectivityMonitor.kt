package com.mattmooneyham.base.android.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Boundary between [NetworkManager] and the platform's connectivity
 * machinery. NetworkManager owns all state and event behavior; this
 * interface owns only the platform hookup, so JVM tests can inject a
 * fake and drive connectivity changes deterministically.
 */
interface ConnectivityMonitor {

    /**
     * Starts monitoring. [onConnectivityChanged] receives validated
     * connectivity soon after and on every change, possibly from a
     * background thread. Calling again while active is a no-op.
     */
    fun start(onConnectivityChanged: (Boolean) -> Unit)

    /** Stops monitoring; a stopped monitor cannot be restarted. */
    fun stop()
}

/**
 * Real implementation wrapping [ConnectivityManager]'s default network
 * callback. Connectivity is reported true only when the network has
 * both INTERNET and VALIDATED capabilities (a usable path, not merely
 * an interface). All state is instance-owned, so per-test components
 * never share monitor state.
 *
 * This is the Android adapter side of the boundary: it is constructed
 * in BaseApplication (the only layer that holds a Context) and handed
 * to the component through AppConfig, keeping the managers Context-free
 * and JVM-testable with a fake.
 *
 * @param context any Context; the application context is retained.
 */
class AndroidConnectivityMonitor(
    context: Context,
) : ConnectivityMonitor {

    private val applicationContext: Context = context.applicationContext

    private var systemConnectivityManager: ConnectivityManager? = null
    private var activeNetworkCallback:
        ConnectivityManager.NetworkCallback? = null

    override fun start(onConnectivityChanged: (Boolean) -> Unit) {
        if (activeNetworkCallback != null) return

        val connectivityManager = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

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

    override fun stop() {
        activeNetworkCallback?.let { callback ->
            systemConnectivityManager?.unregisterNetworkCallback(callback)
        }
        activeNetworkCallback = null
        systemConnectivityManager = null
    }
}

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
