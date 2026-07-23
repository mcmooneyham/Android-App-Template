package com.mattmooneyham.base.android.managers

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob

// DataStore requires this exact file extension; it is checked at runtime.
const val DATA_STORE_FILE_NAME = "base_app.preferences_pb"

/**
 * The scope DataStore runs its IO on. Created by whoever owns the store
 * (the AppComponent) and cancelled in its close(), so no scope hides
 * inside the factory where teardown cannot reach it.
 *
 * The explicit CoroutineExceptionHandler is load-bearing: DataStore's
 * default scope has none. With the handler, storage failures surface
 * to readers as flow errors, which the collectors already catch.
 */
fun createDataStoreScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, _ ->
            // Never let a storage failure take down the process.
        },
)

/**
 * Creates the Preferences DataStore backing [DataStoreManager]. DataStore
 * allows only ONE instance per file per process, so this is called
 * exactly once per store file, by
 * [com.mattmooneyham.base.android.di.AppComponent], and cached there.
 *
 * @param coroutineScope the store's IO scope (see [createDataStoreScope]);
 *   the caller keeps it and cancels it on component close.
 */
fun createPreferencesDataStore(
    coroutineScope: CoroutineScope,
    produceFile: () -> File,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        // A corrupt store falls back to defaults instead of failing reads.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = coroutineScope,
        produceFile = {
            val storeFile = produceFile()
            // Pre-create an empty store (a valid, empty Preferences file)
            // so DataStore's first read never throws FileNotFoundException.
            runCatching {
                if (!storeFile.exists()) {
                    storeFile.parentFile?.mkdirs()
                    storeFile.createNewFile()
                }
            }
            storeFile
        },
    )
