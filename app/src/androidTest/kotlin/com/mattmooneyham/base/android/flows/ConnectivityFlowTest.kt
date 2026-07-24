package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkConnectivityChanged
import com.mattmooneyham.base.android.ui.R
import org.junit.Rule
import org.junit.Test

/**
 * Live event-bus-to-UI binding: publishing on the real EventManager
 * (public API, exactly what the ConnectivityManager does) must flip the
 * HeroCard's connectivity chip with no viewmodel in between. Needs the
 * device's real network to resolve as online first, so the test
 * self-skips (assumeNetworkOnline) on an offline device.
 */
class ConnectivityFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectivityEventsDriveTheStatusChipDirectly() {
        // The device's real network resolves first; skip when offline.
        composeRule.assumeNetworkOnline()

        appComponent.eventManager.trigger(NetworkConnectivityChanged, false)
        composeRule.waitForText(appString(R.string.status_offline))

        appComponent.eventManager.trigger(NetworkConnectivityChanged, true)
        composeRule.waitForText(appString(R.string.status_online))
    }
}
