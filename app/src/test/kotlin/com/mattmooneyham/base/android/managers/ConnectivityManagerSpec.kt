package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.connectivityManager.NetworkConnectivityChanged
import com.mattmooneyham.base.android.testkit.FakeConnectivityMonitor
import com.mattmooneyham.base.android.testkit.TestAppContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ConnectivityManager's publishing contract: duplicate platform
 * reports never reach the bus, so the connectivity stream is
 * strictly alternating. That property is what makes edge detection
 * (JokeManager's reconnect choreography) safe over the lossy
 * DROP_OLDEST buffer: any suffix that survives a drop still
 * contains every false-to-true adjacency.
 */
class ConnectivityManagerSpec {

    private var testContext: TestAppContext? = null

    private fun startApp(): TestAppContext =
        TestAppContext().also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    @Test
    fun `duplicate monitor reports publish exactly once`() =
        runBlocking<Unit> {
            val app = startApp()
            val recorder = app.newRecorder()
                .record(NetworkConnectivityChanged)

            // The monitor's start-time report of the CURRENT state
            // (offline, the fake's default world) is the first fact.
            assertEquals(
                false,
                recorder.expectState(NetworkConnectivityChanged),
            )

            // A change publishes; the duplicates that follow do not
            // (the platform reports duplicate states routinely).
            app.connectivityMonitor.setConnected(true)
            assertEquals(
                true,
                recorder.expectState(NetworkConnectivityChanged),
            )
            app.connectivityMonitor.setConnected(true)
            app.connectivityMonitor.setConnected(true)
            recorder.assertNoEvent(NetworkConnectivityChanged)

            // The next genuine change still lands.
            app.connectivityMonitor.setConnected(false)
            assertEquals(
                false,
                recorder.expectState(NetworkConnectivityChanged),
            )
        }

    @Test
    fun `a boot while online is one fact, never a reconnect edge`() =
        runBlocking<Unit> {
            // The device is online BEFORE the app starts: the
            // monitor's first report is true, and no fabricated
            // "offline" precedes it, so reconnect choreography can
            // never mistake startup for a restored connection.
            val app = TestAppContext(
                connectivityMonitor = FakeConnectivityMonitor(
                    initialConnectivity = true,
                ),
            ).also { testContext = it }
            val recorder = app.newRecorder()
                .record(NetworkConnectivityChanged)

            assertEquals(
                true,
                recorder.expectState(NetworkConnectivityChanged),
            )
            recorder.assertNoEvent(NetworkConnectivityChanged)
        }
}
