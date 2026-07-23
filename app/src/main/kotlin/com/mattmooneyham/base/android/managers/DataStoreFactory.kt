package com.mattmooneyham.base.android.managers

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

// DataStore requires this exact file extension; it is checked at runtime.
const val DATA_STORE_FILE_NAME = "base_sdk.preferences_pb"

/**
 * Creates the Preferences DataStore backing [DataStoreManager]. DataStore
 * allows only ONE instance per file per process, so this is called
 * exactly once, by BaseSdk.initialize, and cached there.
 *
 * The explicit scope is load-bearing: DataStore's default scope has no
 * CoroutineExceptionHandler. With the handler, storage failures surface
 * to readers as flow errors, which the collectors already catch.
 */
fun createPreferencesDataStore(
    producePath: () -> String,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        // A corrupt store falls back to defaults instead of failing reads.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, _ ->
                    // Never let a storage failure take down the process.
                },
        ),
        produceFile = {
            val storeFilePath = producePath().toPath()
            // Pre-create an empty store (a valid, empty Preferences file)
            // so DataStore's first read never throws FileNotFoundException.
            runCatching {
                if (!FileSystem.SYSTEM.exists(storeFilePath)) {
                    storeFilePath.parent?.let { parentDirectory ->
                        FileSystem.SYSTEM.createDirectories(parentDirectory)
                    }
                    FileSystem.SYSTEM.write(storeFilePath) { }
                }
            }
            storeFilePath
        },
    )
