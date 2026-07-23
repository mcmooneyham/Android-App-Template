package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.managers.NetworkConnectivityChanged
import org.junit.Rule
import org.junit.Test

/**
 * Live event-bus-to-UI binding: publishing on the real EventManager
 * (public API, exactly what the NetworkManager does) must flip the
 * HeroCard's connectivity chip with no viewmodel in between.
 */
class ConnectivityFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectivityEventsDriveTheStatusChipDirectly() {
        // The device's real network resolves first.
        composeRule.waitForText("Online")

        appComponent.eventManager.trigger(NetworkConnectivityChanged, false)
        composeRule.waitForText("Offline")

        appComponent.eventManager.trigger(NetworkConnectivityChanged, true)
        composeRule.waitForText("Online")
    }
}
