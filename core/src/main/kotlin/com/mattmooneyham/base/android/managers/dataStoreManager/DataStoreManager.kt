package com.mattmooneyham.base.android.managers.dataStoreManager

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventLifetime
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.logManager.LogManager
import com.mattmooneyham.base.android.managers.logManager.bridgeRetryReporter
import com.mattmooneyham.base.android.util.RetryPolicy
import com.mattmooneyham.base.android.util.retryForever
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// State: the persisted welcome flag; emitted on load and every change.
object HasSeenWelcomeChanged : StateKey<Boolean>(
    eventName = "datastore.HasSeenWelcomeChanged",
    payloadType = Boolean::class,
    // APP lifetime: the preference file is device-persistent and the
    // bridge below re-publishes only on VALUE CHANGES, so a SESSION
    // key would go dark after resetSessionReplayCaches() until the
    // next changing write (same reasoning as FeatureFlagsChanged).
    lifetime = EventLifetime.APP,
)

/**
 * Typed facade over the shared Preferences DataStore. Each preference is a
 * [Flow] property paired with a suspend setter; add project preferences by
 * following the same pattern. Provided as a singleton via AppComponent
 * (in :app).
 *
 * Event-driven contract: every preference is streamed through
 * [EventManager], so listeners receive the stored value on startup and
 * again on every change; suspend setters are the write path. Every write
 * and every stream failure is logged with full call-site context.
 */
class DataStoreManager(
    private val dataStore: DataStore<Preferences>,
    private val eventManager: EventManager,
    private val logManager: LogManager,
) : ConfinedManager(
    managerName = "DataStoreManager",
    failureLogManager = logManager,
) {

    companion object {
        private val KEY_HAS_SEEN_WELCOME =
            booleanPreferencesKey("has_seen_welcome")
    }

    // distinctUntilChanged: DataStore re-emits on EVERY write
    // transaction, so without it an unrelated preference edit would
    // re-publish this event (and pay the trigger-trace cost) with an
    // unchanged value.
    val hasSeenWelcome: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_HAS_SEEN_WELCOME] ?: false }
        .distinctUntilChanged()

    init {
        // Publish persisted values through the event bus: the initial
        // read and every subsequent edit reach listeners as events. A
        // read failure must neither take the collector down NOR end
        // the bridge for the life of the process (Flow.catch would do
        // exactly that: it terminates the upstream): retryForever
        // resubscribes with capped backoff, so a transient IO failure
        // costs a delay, not the feature. Each outage's first failure
        // logs at ERROR (a counted non-fatal via the telemetry
        // funnel); repeats log at WARN so a permanently broken disk
        // cannot spam the crash backend. trigger() is non-throwing by
        // design, so only storage failures drive the loop.
        managerScope.launch {
            retryForever(
                policy = RetryPolicy(maxAttempts = null),
                onRetry = logManager
                    .bridgeRetryReporter("hasSeenWelcome bridge"),
            ) { onHealthy ->
                hasSeenWelcome.collect { hasSeen ->
                    onHealthy()
                    eventManager.trigger(HasSeenWelcomeChanged, hasSeen)
                }
            }
        }
    }

    // No close() override needed: the inherited one stops streaming
    // preference changes onto the bus. The DataStore itself runs on the
    // scope its creator owns (see createDataStoreScope), which the
    // AppComponent cancels separately.

    // Write failures are logged here rather than surfaced to callers:
    // UI call sites fire-and-forget these writes, so this is the one
    // place every failure is guaranteed to become visible.
    suspend fun setHasSeenWelcome(value: Boolean) {
        runCatching {
            dataStore.edit { prefs -> prefs[KEY_HAS_SEEN_WELCOME] = value }
        }.onSuccess {
            logManager.debug("hasSeenWelcome set to $value")
        }.onFailure { writeFailure ->
            // The throwable rides along so the ERROR prints its stack
            // trace and the telemetry funnel counts the real failure.
            logManager.error(
                "hasSeenWelcome write failed: ${writeFailure.message}",
                throwable = writeFailure,
            )
        }
    }

    /** Removes the welcome flag; [hasSeenWelcome] falls back to false. */
    suspend fun clearHasSeenWelcome() {
        runCatching {
            dataStore.edit { prefs -> prefs.remove(KEY_HAS_SEEN_WELCOME) }
        }.onSuccess {
            logManager.debug("hasSeenWelcome removed")
        }.onFailure { writeFailure ->
            logManager.error(
                "hasSeenWelcome clear failed: ${writeFailure.message}",
                throwable = writeFailure,
            )
        }
    }

}
