package com.mattmooneyham.base.android.managers.templateManager

import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkConnectivityChanged
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.SignalKey
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.featureFlagManager.BooleanFlag
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
import kotlinx.coroutines.launch

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// core/src/main/kotlin/.../managers/<yourFeature>Manager/, rename
// Template -> YourFeature, follow the numbered STEP comments, then
// delete every banner and STEP comment. The result should read like
// JokeManager: lean code whose comments state only what the code
// cannot.
//
// WIRING (the lines your feature PR adds, nothing else):
//
// AppComponent.kt, at the END of the feature-managers region:
// ```
// val yourFeatureManager = YourFeatureManager(
//     port = config.yourFeaturePort,
//     logManager = logManager,
//     eventManager = eventManager,
//     featureFlagManager = featureFlagManager,
// ).registered()
// ```
// AppModule.kt, appended at the end:
// ```
// @Provides
// fun provideYourFeatureManager(
//     component: AppComponent,
// ): YourFeatureManager = component.yourFeatureManager
// ```
// (The WiringConventionsGuardTest fails until the provider exists.)
// ===================================================================

// STEP 1: DECLARE YOUR KEYS beside the manager, never in a shared
// registry file. Naming: "namespace.EventName" where the lowercase
// namespace names this manager. StateKey for replayed state (late
// subscribers get the latest value); SignalKey for one-shot
// notifications that must NOT replay. Default SESSION lifetime unless
// the value is a device/process fact (see EventLifetime).
object TemplateStateChanged : StateKey<TemplateState>(
    eventName = "template.StateChanged",
    payloadType = TemplateState::class,
)

object TemplateHistoryCleared : SignalKey(
    eventName = "template.HistoryCleared",
)

// STEP 2: DECLARE YOUR FLAGS beside the manager too. In real code the
// flag must ALSO be listed in AppFlags.all (the registry guard fails
// the build otherwise); this module is exempt from that guard.
object TemplateEnrichmentFlag : BooleanFlag(
    flagKey = "template.enrichment",
    default = false,
    description = "Enrich template readings with derived data",
)

// STEP 3: THE PAYLOAD. Strongly prefer bundling a feature's related
// state into one payload type, so a single subscription tells a
// screen the whole story; split into multiple keys with their own
// payloads when the concerns are genuinely separate (different
// consumers, different cadences), never just to save a field.
// Immutable, additive evolution only: new fields get defaults (see
// the payload-evolution rules in ARCHITECTURE-SCALING.md).
data class TemplateState(
    val latestReading: Int? = null,
    val readingCount: Int = 0,
    val isEnriched: Boolean = false,
)

/**
 * STEP 4: THE MANAGER. Extends ConfinedManager for the concurrency
 * rails: managerScope runs at most one coroutine at a time, so the
 * plain `var` fields below are race-free WITHOUT locks, provided
 * every mutation happens on the confinement.
 *
 * The manager PUBLISHES; everything else listens. Views observe via
 * eventState/eventStateOrNull, the viewmodel forwards user actions
 * back, and no other manager ever holds a reference to this one
 * (cross-feature conversation rides published events).
 *
 * Constructor budget: five parameters, a bus handle counts as one.
 * EventManager, LogManager, and FeatureFlagManager are the
 * infrastructure trio managers may receive directly.
 */
class TemplateManager(
    private val port: TemplatePort,
    private val logManager: LogManager,
    private val eventManager: EventManager,
    private val featureFlagManager: FeatureFlagManager,
) : ConfinedManager(
    managerName = "TemplateManager",
    failureLogManager = logManager,
) {

    // STEP 5: CONFINED STATE. Touched only inside managerScope (or in
    // init/start before any coroutine exists), never from callbacks
    // directly: callbacks hop onto the confinement first.
    private var latestReading: Int? = null
    private var readingCount = 0

    init {
        // STEP 6: THE INIT BUDGET. Construction may allocate,
        // subscribe, and register cheap callbacks; it must NOT fetch
        // or do unbounded-latency work (that belongs in start(); a
        // guard test fails constructors that fetch).
        publishState()

        // Port callbacks arrive on any thread: hop onto the
        // confinement before touching the fields above.
        port.start { reading ->
            managerScope.launch { recordReading(reading) }
        }

        // STEP 7 (optional): CHOREOGRAPHY. React to another manager's
        // events by subscribing with the manager ITSELF as owner (the
        // subscription lives exactly as long as the manager). Inside
        // the lambda, members resolve against the RECEIVER (the
        // weakly held owner), so the callback never pins the manager.
        eventManager.listenTo(
            NetworkConnectivityChanged,
            owner = this,
        ) { isConnected ->
            managerScope.launch { reactToConnectivity(isConnected) }
        }
    }

    /**
     * STEP 8: START. First loads and warmups run here, called once by
     * AppComponent.start() after every manager exists (idempotent at
     * the component level). Specs that construct the manager directly
     * call start() themselves.
     */
    override fun start() {
        managerScope.launch {
            logManager.info("Template warmup complete")
            // A real warmup publishes FRESH data. If nothing changed
            // since the construction seed, the bus suppresses this
            // republish: unchanged state is never re-delivered.
            publishState()
        }
    }

    /**
     * STEP 9: PUBLIC API. Fire-and-forget and callable from ANY
     * thread: the body immediately launches onto the confinement, so
     * callers never block and state stays race-free.
     */
    fun clearHistory() {
        managerScope.launch {
            latestReading = null
            readingCount = 0
            publishState()
            // Signal AFTER the fact it announces is true.
            eventManager.trigger(TemplateHistoryCleared)
        }
    }

    /** Runs on the confinement (see the launch in the port hookup). */
    private fun recordReading(reading: Int) {
        latestReading = reading
        readingCount += 1
        publishState()
    }

    /** Runs on the confinement. Choreography reactions stay small:
     * decide, then reuse the public path. */
    private fun reactToConnectivity(isConnected: Boolean) {
        if (!isConnected) {
            logManager.info("Template readings may go stale offline")
        }
    }

    // STEP 10: FLAGS are read at DECISION time, never cached at
    // construction, so provider updates and debug overrides apply to
    // the very next decision.
    private fun publishState() {
        eventManager.trigger(
            TemplateStateChanged,
            TemplateState(
                latestReading = latestReading,
                readingCount = readingCount,
                isEnriched = featureFlagManager
                    .isEnabled(TemplateEnrichmentFlag),
            ),
        )
    }

    /** STEP 11: CLOSE. Release the port, then the rails; called by
     * the component's teardown registry in reverse construction
     * order. */
    override fun close() {
        port.stop()
        super.close()
    }
}
