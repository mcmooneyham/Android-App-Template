package com.mattmooneyham.base.android.platform

import android.content.Context
import com.mattmooneyham.base.android.managers.templateManager.TemplatePort

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// app/src/main/kotlin/.../platform/, rename Template -> YourBoundary,
// and delete these banners. Adapters are the ONLY layer that may
// import android.* for a manager's benefit; they are constructed in
// BaseApplication (the one place that holds a Context) and handed to
// the component through AppConfig, never injected anywhere else.
// ===================================================================

/**
 * STEP 1: NAME THE TECH. The port is named for the capability
 * (TemplatePort); the adapter names what actually implements it
 * (Android<Tech>Adapter, ThetaAdapter, FirebaseFlagProvider, ...).
 *
 * STEP 2: STAY LOGIC-FREE. An adapter converts between the SDK's
 * callbacks/types and the port's, and NOTHING else: no state the
 * manager should own, no decisions, no retries (the manager owns
 * policy; util/Retry owns backoff). If an adapter grows an if-tree,
 * the logic belongs in the manager behind the port.
 *
 * STEP 3: OWN YOUR INSTANCE STATE. Keep every registration handle in
 * instance fields (never companions), so per-test components can
 * never share adapter state.
 *
 * @param context any Context; only the application context is kept.
 */
class AndroidTemplateAdapter(
    context: Context,
) : TemplatePort {

    private val applicationContext: Context = context.applicationContext

    private var isStarted = false

    override fun start(onReading: (Int) -> Unit) {
        // Mirror the port contract: starting twice is a no-op.
        if (isStarted) return
        isStarted = true
        // A real adapter registers an SDK listener here and forwards
        // its values through onReading, converting SDK types to the
        // port's plain types at this boundary and nowhere else.
        onReading(applicationContext.resources.configuration.densityDpi)
    }

    override fun stop() {
        // Unregister the SDK listener here.
        isStarted = false
    }
}
