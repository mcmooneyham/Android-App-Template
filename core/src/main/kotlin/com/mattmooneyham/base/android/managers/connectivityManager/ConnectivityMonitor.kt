package com.mattmooneyham.base.android.managers.connectivityManager

/**
 * Boundary between [ConnectivityManager] and the platform's
 * connectivity machinery. ConnectivityManager owns all state and
 * event behavior; this
 * interface owns only the platform hookup, so JVM tests can inject a
 * fake and drive connectivity changes deterministically. The real
 * adapter (AndroidConnectivityMonitor, in :app's platform package) is
 * built in BaseApplication and arrives through AppConfig; this module
 * has no Android classpath, so the compiler keeps it that way.
 */
interface ConnectivityMonitor {

    /**
     * Starts monitoring. [onConnectivityChanged] receives the CURRENT
     * validated connectivity as its first report, during start, and
     * then every change, possibly from a background thread. The first
     * report being the device's real state (never an assumed default)
     * is part of the contract: it is what keeps a boot-while-online
     * from ever looking like an offline-to-online reconnect edge.
     * Calling again while active is a no-op.
     */
    fun start(onConnectivityChanged: (Boolean) -> Unit)

    /** Stops monitoring; a stopped monitor cannot be restarted. */
    fun stop()
}
