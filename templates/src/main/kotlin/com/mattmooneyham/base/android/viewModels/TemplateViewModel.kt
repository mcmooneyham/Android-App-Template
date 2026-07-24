package com.mattmooneyham.base.android.viewModels

import androidx.lifecycle.ViewModel
import com.mattmooneyham.base.android.managers.templateManager.TemplateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// ui/src/main/kotlin/.../viewModels/, rename Template -> YourFeature,
// and delete these banners. The manager binding resolves once your
// feature's provider exists in AppModule (see TemplateManager.kt's
// wiring block).
// ===================================================================

/**
 * STEP 1: VIEWMODELS ARE THIN. They hold WRITE ACTIONS and the rare
 * derived value; they do NOT relay event state. Views observe the bus
 * directly via eventState/eventStateOrNull, so a viewmodel that only
 * mirrors a key back to its screen is a smell, not a layer.
 *
 * STEP 2: WHAT BELONGS HERE: user actions forwarded to managers
 * (fire-and-forget), suspend orchestration a tap needs (see
 * SettingsViewModel.writeLogExportSnapshot), and injected app
 * metadata the UI module cannot read itself (BuildInfo).
 *
 * STEP 3: NEVER navigate from here. Publish facts through managers;
 * the shell's RouteOnAppEvents maps facts to router calls, and direct
 * user navigation stays a semantic lambda wired at the shell.
 */
@HiltViewModel
class TemplateViewModel @Inject constructor(
    private val templateManager: TemplateManager,
) : ViewModel() {

    /** One line per user action: delegate, never duplicate logic. */
    fun clearHistory() {
        templateManager.clearHistory()
    }
}
