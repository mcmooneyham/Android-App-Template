package com.mattmooneyham.base.android.managers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// State: the persisted welcome flag; emitted on load and every change.
object HasSeenWelcomeChanged : StateKey<Boolean>(
    eventName = "datastore.HasSeenWelcomeChanged",
    payloadType = Boolean::class,
)

/**
 * Typed facade over the shared Preferences DataStore. Each preference is a
 * [Flow] property paired with a suspend setter; add project preferences by
 * following the same pattern. Provided as a singleton via
 * [com.mattmooneyham.base.android.di.AppComponent].
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
        // Publish persisted values through the event bus: the initial read
        // and every subsequent edit reach listeners as events. The catch is
        // load-bearing: storage reads CAN fail, and a failure must never
        // take the collectors down.
        managerScope.launch {
            hasSeenWelcome
                .catch { throwable ->
                    logManager.warn(
                        "hasSeenWelcome stream failed: ${throwable.message}",
                    )
                }
                .collect { hasSeen ->
                    eventManager.trigger(HasSeenWelcomeChanged, hasSeen)
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
            logManager.error(
                "hasSeenWelcome write failed: ${writeFailure.message}",
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
            )
        }
    }

}
