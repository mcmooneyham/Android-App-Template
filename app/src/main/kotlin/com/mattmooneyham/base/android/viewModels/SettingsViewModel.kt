package com.mattmooneyham.base.android.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mattmooneyham.base.android.BuildConfig
import com.mattmooneyham.base.android.managers.DataStoreManager
import com.mattmooneyham.base.android.managers.LogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings tab: preference and log maintenance through the SDK
 * singletons, plus the app's version info for the About section.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStoreManager: DataStoreManager,
    private val logManager: LogManager,
) : ViewModel() {

    /** Marketing version shown in About (from defaultConfig.versionName). */
    val appVersionName: String = BuildConfig.VERSION_NAME

    /** Epoch-milliseconds build stamp generated at build time. */
    val buildTimestampSeconds: Long = BuildConfig.BUILD_TIMESTAMP_SECONDS

    /** Clears the welcome flag; Home shows "First launch" until relaunch. */
    fun clearWelcomeFlag() {
        viewModelScope.launch {
            dataStoreManager.clearHasSeenWelcome()
            logManager.info("Welcome flag cleared from Settings")
        }
    }

    /**
     * Log contents for the share sheet; flushes the background writer
     * first so the export contains every line logged so far.
     */
    suspend fun readLogsForExport(): String {
        logManager.flush()
        return logManager.readLogContents()
    }

    /** Deletes the SDK log file. */
    fun clearLogs() {
        logManager.clearLogs()
        logManager.info("Log file cleared from Settings")
    }

}
