package com.mattmooneyham.base.android.managers.featureFlagManager

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventLifetime
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.util.RetryPolicy
import com.mattmooneyham.base.android.util.retryForever
import kotlin.time.Duration
import kotlinx.coroutines.launch

// State: the resolved value and deciding layer of every declared flag.
// APP lifetime: the snapshot describes build and device facts (compiled
// defaults, device-persisted debug overrides, provider values), not
// user state, so it survives resetSessionReplayCaches. Revisit as
// SESSION if a user-targeted provider is ever wired (see
// ARCHITECTURE-SCALING.md).
object FeatureFlagsChanged : StateKey<FlagSnapshot>(
    eventName = "flags.Changed",
    payloadType = FlagSnapshot::class,
    lifetime = EventLifetime.APP,
)

/** Which resolution layer decided a flag's value. */
enum class FlagSource { DEFAULT, PROVIDER, OVERRIDE }

/** One flag's resolved value plus the layer that decided it. */
data class ResolvedFlag(
    val enabled: Boolean,
    val source: FlagSource,
)

/** Payload of [FeatureFlagsChanged]: every declared flag, resolved,
 * keyed by [BooleanFlag.flagKey]. */
data class FlagSnapshot(
    val flagsByKey: Map<String, ResolvedFlag> = emptyMap(),
)

/**
 * Owns feature-flag resolution for the app. Three value layers, most
 * specific wins:
 *
 * 1. OVERRIDE: a local value set from the Settings debug UI via
 *    [setOverride], persisted in [overridesStore] so it survives
 *    relaunches. DEBUG BUILDS ONLY: release builds pass a null store
 *    (the AppComponent never creates one), so they are structurally
 *    locked to the layers below.
 * 2. PROVIDER: the latest map from the [FeatureFlagProvider] boundary
 *    (no-op in the template; a remote-config adapter later).
 * 3. DEFAULT: the compiled [BooleanFlag.default].
 *
 * Every resolution change publishes [FeatureFlagsChanged] with the
 * full snapshot; managers read [isEnabled] synchronously at decision
 * time (never cache the answer at construction: override and provider
 * values land asynchronously shortly after launch), and views observe
 * via the `flagState` composable.
 *
 * Provided as a singleton via AppComponent (in :app).
 */
class FeatureFlagManager(
    private val flags: List<BooleanFlag>,
    private val overridesStore: DataStore<Preferences>?,
    private val provider: FeatureFlagProvider,
    private val eventManager: EventManager,
    private val logManager: LogManager,
) : ConfinedManager(
    managerName = "FeatureFlagManager",
    failureLogManager = logManager,
) {

    // Layered inputs, confined to the manager's serial dispatcher.
    private var providerValues: Map<String, Boolean> = emptyMap()
    private var overrideValues: Map<String, Boolean> = emptyMap()

    // The resolved output. Read by isEnabled from ANY thread, written
    // only inside publishResolvedFlags (constructor seed, then the
    // confinement), hence volatile rather than confined.
    @Volatile
    private var resolvedFlags: Map<String, ResolvedFlag> = emptyMap()

    init {
        // Seed synchronously with compiled defaults so isEnabled and
        // the replay cache are correct from the first instant; the
        // async layers below republish when they land. Safe on the
        // constructing thread: no managerScope coroutine exists yet
        // (the same seed-before-monitor shape as the connectivity
        // manager).
        publishResolvedFlags()

        // Debug builds stream persisted overrides (initial load plus
        // every setOverride edit) and re-resolve. Release builds have
        // no store, so this layer never exists. The bridge must never
        // die (Flow.catch would end it on the first read failure):
        // retryForever resubscribes with capped backoff, first
        // failure per outage at ERROR, repeats at WARN.
        if (overridesStore != null) {
            managerScope.launch {
                retryForever(
                    policy = RetryPolicy(maxAttempts = null),
                    onRetry = ::reportBridgeFailure,
                ) { onHealthy ->
                    overridesStore.data.collect { preferences ->
                        onHealthy()
                        overrideValues = preferences.asMap().entries
                            .mapNotNull { (preferenceKey, value) ->
                                (value as? Boolean)?.let { enabled ->
                                    preferenceKey.name to enabled
                                }
                            }
                            .toMap()
                        publishResolvedFlags()
                    }
                }
            }
        }

        provider.start { updatedFlagValues ->
            // Provider callbacks may arrive on any thread; hop onto
            // the confinement before touching layered state.
            managerScope.launch {
                providerValues = updatedFlagValues
                publishResolvedFlags()
            }
        }
    }

    /**
     * The flag's current resolved value, callable from any thread.
     * Read at DECISION TIME, not at construction: overrides and
     * provider values land asynchronously shortly after launch.
     */
    fun isEnabled(flag: BooleanFlag): Boolean =
        resolvedFlags[flag.flagKey]?.enabled ?: flag.default

    /**
     * Sets (true/false) or clears (null) a local override for [flag].
     * Fire-and-forget from any thread; the persisted edit streams back
     * through the store collector, which re-resolves and publishes.
     * Ignored (with a log line) in builds without an override store:
     * release stays locked to provider/default values.
     */
    fun setOverride(flag: BooleanFlag, enabled: Boolean?) {
        val store = overridesStore
        if (store == null) {
            logManager.warn(
                "Ignored override for '${flag.flagKey}': this build " +
                    "is locked to default flag values",
            )
            return
        }
        managerScope.launch {
            runCatching {
                store.edit { preferences ->
                    val overrideKey = booleanPreferencesKey(flag.flagKey)
                    if (enabled == null) {
                        preferences.remove(overrideKey)
                    } else {
                        preferences[overrideKey] = enabled
                    }
                }
            }.onSuccess {
                logManager.info(
                    "Flag '${flag.flagKey}' override " +
                        (enabled?.let { "set to $it" } ?: "cleared"),
                )
            }.onFailure { writeFailure ->
                logManager.error(
                    "Override write for '${flag.flagKey}' failed: " +
                        "${writeFailure.message}",
                    throwable = writeFailure,
                )
            }
        }
    }

    /** First failure per outage at ERROR, repeats at WARN. */
    private fun reportBridgeFailure(
        attempt: Int,
        storeFailure: Throwable,
        nextDelay: Duration,
    ) {
        if (attempt == 1) {
            logManager.error(
                "Flag override bridge failed; resubscribing in " +
                    "$nextDelay",
                throwable = storeFailure,
            )
        } else {
            logManager.warn(
                "Flag override bridge failed again " +
                    "(attempt $attempt); resubscribing in $nextDelay",
            )
        }
    }

    /** Stops the provider, then cancels the manager's coroutines. */
    override fun close() {
        provider.stop()
        super.close()
    }

    /** Re-resolves every declared flag and publishes on change. */
    private fun publishResolvedFlags() {
        val resolved = flags.associate { flag ->
            val overrideValue = overrideValues[flag.flagKey]
            val providerValue = providerValues[flag.flagKey]
            flag.flagKey to when {
                overrideValue != null ->
                    ResolvedFlag(overrideValue, FlagSource.OVERRIDE)
                providerValue != null ->
                    ResolvedFlag(providerValue, FlagSource.PROVIDER)
                else ->
                    ResolvedFlag(flag.default, FlagSource.DEFAULT)
            }
        }
        // Dedupe: the dedicated store re-emits on every edit and
        // providers may resend identical maps; unchanged resolutions
        // produce no event traffic.
        if (resolved == resolvedFlags) return
        resolvedFlags = resolved
        eventManager.trigger(FeatureFlagsChanged, FlagSnapshot(resolved))
    }

    companion object {
        // DataStore requires this exact extension; the file sits beside
        // the app's main preferences store but stays its own store
        // (DataStore allows one instance per file per process).
        const val FLAG_OVERRIDES_FILE_NAME = "feature_flags.preferences_pb"
    }
}
