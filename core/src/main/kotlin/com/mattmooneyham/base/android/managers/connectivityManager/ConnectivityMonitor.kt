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
     * Starts monitoring. [onConnectivityChanged] receives validated
     * connectivity soon after and on every change, possibly from a
     * background thread. Calling again while active is a no-op.
     */
    fun start(onConnectivityChanged: (Boolean) -> Unit)

    /** Stops monitoring; a stopped monitor cannot be restarted. */
    fun stop()
}
