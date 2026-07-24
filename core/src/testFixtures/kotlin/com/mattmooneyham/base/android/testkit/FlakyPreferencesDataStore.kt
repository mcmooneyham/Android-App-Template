package com.mattmooneyham.base.android.testkit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Hand-written fault-injection wrapper for the DataStore boundary
 * (no mocking framework, per the testkit's rule): the first
 * [failuresToServe] collections of [data] throw an IOException as a
 * real mid-read storage failure would; later collections delegate to
 * the real store. Writes always pass through, so a spec can mutate
 * state while the read side is "broken".
 */
class FlakyPreferencesDataStore(
    private val delegate: DataStore<Preferences>,
    failuresToServe: Int,
) : DataStore<Preferences> {

    private val remainingFailures = AtomicInteger(failuresToServe)

    /** Collections attempted so far, failed and successful. */
    val collectionCount = AtomicInteger(0)

    override val data: Flow<Preferences> = flow {
        collectionCount.incrementAndGet()
        if (remainingFailures.getAndDecrement() > 0) {
            throw IOException("simulated storage read failure")
        }
        emitAll(delegate.data)
    }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = delegate.updateData(transform)
}
