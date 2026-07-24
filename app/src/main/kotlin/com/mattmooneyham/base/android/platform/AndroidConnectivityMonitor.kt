package com.mattmooneyham.base.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mattmooneyham.base.android.managers.connectivityManager.ConnectivityMonitor

/**
 * Real implementation of the [ConnectivityMonitor] port, wrapping
 * [ConnectivityManager]'s default network callback. Connectivity is
 * reported true only when the network has both INTERNET and VALIDATED
 * capabilities (a usable path, not merely an interface). All state is
 * instance-owned, so per-test components never share monitor state.
 *
 * This is the Android adapter side of the boundary: it is constructed
 * in BaseApplication (the only layer that holds a Context) and handed
 * to the component through AppConfig. The port itself lives in :core,
 * which has no Android classpath at all.
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
            // Deliberately NO onAvailable override: it fires before
            // capabilities are known, and reporting true there would
            // break the VALIDATED-only contract (a captive portal
            // would flash "online"). onCapabilitiesChanged is
            // guaranteed to follow onAvailable immediately, so the
            // first honest answer arrives right after.
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
        // Port contract: the FIRST report is the device's actual
        // current state, queried synchronously, so consumers start
        // from truth instead of a fabricated "offline" that the first
        // callback would immediately contradict.
        val currentCapabilities = connectivityManager
            .getNetworkCapabilities(connectivityManager.activeNetwork)
        onConnectivityChanged(
            currentCapabilities?.hasValidatedInternet() == true,
        )
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
