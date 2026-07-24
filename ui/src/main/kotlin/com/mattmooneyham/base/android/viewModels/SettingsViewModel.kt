package com.mattmooneyham.base.android.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mattmooneyham.base.android.constants.BuildInfo
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.featureFlagManager.BooleanFlag
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings tab: preference and log maintenance through the app
 * core managers, plus the app's version info for the About section and
 * the debug-only feature-flag overrides. Build metadata arrives as an
 * injected [BuildInfo]: this module has no application BuildConfig,
 * and the seam keeps About assertions exact in tests.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStoreManager: DataStoreManager,
    private val logManager: LogManager,
    private val featureFlagManager: FeatureFlagManager,
    buildInfo: BuildInfo,
) : ViewModel() {

    /** Marketing version shown in About (from defaultConfig.versionName). */
    val appVersionName: String = buildInfo.versionName

    /** Epoch-seconds build stamp generated at build time. */
    val buildTimestampSeconds: Long = buildInfo.buildTimestampSeconds

    /** Debug builds unlock the Settings Debug section (flag overrides). */
    val isDebugBuild: Boolean = buildInfo.isDebugBuild

    /** Clears the welcome flag; Home shows "First launch" until relaunch. */
    fun clearWelcomeFlag() {
        viewModelScope.launch {
            dataStoreManager.clearHasSeenWelcome()
            logManager.info("Welcome flag cleared from Settings")
        }
    }

    /**
     * Writes the complete log snapshot (rotated history plus live
     * file) for URI-based sharing and returns its path, or null when
     * there is nothing to share. Flushing happens inside the
     * LogManager, so the snapshot includes every line logged before
     * the tap.
     */
    suspend fun writeLogExportSnapshot(): String? =
        logManager.writeExportSnapshot()

    /** Deletes the app's log file. */
    fun clearLogs() {
        logManager.clearLogs()
        logManager.info("Log file cleared from Settings")
    }

    /**
     * Sets (true/false) or clears (null) a local flag override from
     * the debug section. The result comes back as a
     * FeatureFlagsChanged event, which the section observes.
     */
    fun setFlagOverride(flag: BooleanFlag, enabled: Boolean?) {
        featureFlagManager.setOverride(flag, enabled)
    }

}
