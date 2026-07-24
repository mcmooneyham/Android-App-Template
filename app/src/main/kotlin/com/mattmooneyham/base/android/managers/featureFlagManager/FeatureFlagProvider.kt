package com.mattmooneyham.base.android.managers.featureFlagManager

/**
 * Boundary between [FeatureFlagManager] and a remote flag backend
 * (Firebase Remote Config, LaunchDarkly, ...). The manager owns all
 * resolution and event behavior; this interface owns only the backend
 * hookup, so JVM tests inject a fake and drive flag changes by hand,
 * mirroring the ConnectivityMonitor boundary.
 *
 * The template ships only the seam plus the no-op default; a real
 * adapter is a future edge class built in BaseApplication and wired
 * through AppConfig.featureFlagProvider (see ARCHITECTURE-SCALING.md).
 */
interface FeatureFlagProvider {

    /**
     * Starts the provider. [onFlagsUpdated] receives the full map of
     * backend values (keyed by [BooleanFlag.flagKey]) whenever they
     * load or change, possibly from a background thread. Keys the app
     * does not declare are ignored. Calling again while active is a
     * no-op.
     */
    fun start(onFlagsUpdated: (Map<String, Boolean>) -> Unit)

    /** Stops the provider; a stopped provider cannot be restarted. */
    fun stop()
}

/** Default provider: supplies nothing, so defaults (and any debug
 * overrides) decide every flag. */
object NoOpFeatureFlagProvider : FeatureFlagProvider {
    override fun start(onFlagsUpdated: (Map<String, Boolean>) -> Unit) =
        Unit

    override fun stop() = Unit
}
